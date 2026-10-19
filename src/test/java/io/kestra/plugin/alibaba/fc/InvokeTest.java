package io.kestra.plugin.alibaba.fc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class InvokeTest {
    private static final String PATH = "/2023-03-30/functions/resize-image/invocations";

    private static WireMockServer wireMock;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void sync() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("x-fc-request-id", "req-1")
                .withBody("{\"resized\":true}")));

        var runContext = runContext();
        var output = task(Invoke.InvocationType.SYNC).run(runContext);

        assertThat(output.getStatusCode(), is(200));
        assertThat(output.getBody(), is("{\"resized\":true}"));
        assertThat(output.getRequestId(), is("req-1"));
        try (var stream = runContext.storage().getFile(output.getUri())) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8), is("{\"resized\":true}"));
        }
        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH))
            .withHeader("x-fc-invocation-type", equalTo("Sync"))
            .withRequestBody(equalToJson("{\"key\":\"landing/photo.png\"}")));
    }

    @Test
    void async() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(aResponse().withStatus(202).withHeader("x-fc-request-id", "req-2")));

        var output = task(Invoke.InvocationType.ASYNC).run(runContext());

        assertThat(output.getStatusCode(), is(202));
        assertThat(output.getRequestId(), is("req-2"));
        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH))
            .withHeader("x-fc-invocation-type", equalTo("Async")));
    }

    @Test
    void failsOnNon2xx() {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"Code\":\"FunctionNotFound\",\"Message\":\"function not found\",\"RequestId\":\"req-3\"}")));

        var e = assertThrows(IllegalStateException.class, () -> task(Invoke.InvocationType.SYNC).run(runContext()));

        assertThat(e.getMessage(), containsString("resize-image"));
        assertThat(e.getMessage(), containsString("404"));
        assertThat(e.getMessage(), containsString("function and qualifier exist"));
    }

    @Test
    void sendsListAndStringPayloads() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("ok")));

        var list = payloadTask(List.of(1, 2));
        list.run(runContext());
        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH)).withRequestBody(equalToJson("[1,2]")));

        wireMock.resetRequests();
        var text = payloadTask("{{ 'hello' | upper }}");
        text.run(runContext());
        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH)).withRequestBody(equalTo("HELLO")));
    }

    @Test
    void rendersPayloadOnlyOnce() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("ok")));
        var variables = Map.<String, Object>of("value", "{{ 1 + 1 }}");

        payloadTask("{{ value }}").run(runContextFactory.of(variables));
        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH)).withRequestBody(equalTo("{{ 1 + 1 }}")));

        wireMock.resetRequests();
        payloadTask(Map.of("key", "{{ value }}")).run(runContextFactory.of(variables));
        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH)).withRequestBody(equalToJson("{\"key\":\"{{ 1 + 1 }}\"}")));

        wireMock.resetRequests();
        payloadTask(List.of("{{ value }}")).run(runContextFactory.of(variables));
        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH)).withRequestBody(equalToJson("[\"{{ 1 + 1 }}\"]")));
    }

    @Test
    void inlinesBodyUpTo512Kb() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("a".repeat(512 * 1024))));
        var atLimit = task(Invoke.InvocationType.SYNC).run(runContext());
        assertThat(atLimit.getBody().length(), is(512 * 1024));

        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("a".repeat(512 * 1024 + 1))));
        var overLimit = task(Invoke.InvocationType.SYNC).run(runContext());
        assertThat(overLimit.getBody(), nullValue());
        assertThat(overLimit.getUri(), notNullValue());
    }

    @Test
    void binaryResponseHasNoBody() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(new byte[]{(byte) 0x89, 'P', 'N', 'G', (byte) 0xff})));
        var output = task(Invoke.InvocationType.SYNC).run(runContext());
        assertThat(output.getBody(), nullValue());
        assertThat(output.getUri(), notNullValue());
    }

    @Test
    void failsWithAClearMessageOnReadTimeout() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("late")));
        var task = Invoke.builder()
            .id(IdUtils.create())
            .type(Invoke.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue("http://127.0.0.1:" + wireMock.port()))
            .functionName(Property.ofValue("resize-image"))
            .timeout(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContext()));

        assertThat(e.getMessage(), containsString("resize-image"));
        assertThat(e.getMessage(), containsString("raise the task `timeout`"));
    }

    @Test
    void failsOnThrottling() {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"Code\":\"TooManyRequests\",\"Message\":\"throttled\",\"RequestId\":\"req-6\"}")));

        var e = assertThrows(IllegalStateException.class, () -> task(Invoke.InvocationType.SYNC).run(runContext()));

        assertThat(e.getMessage(), containsString("throttled, retry later"));
    }

    @Test
    void failsWhenTheFunctionThrows() {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("X-Fc-Error-Type", "UnhandledInvocationError")
                .withHeader("x-fc-request-id", "req-5")
                .withBody("{\"errorMessage\":\"boom\"}")));

        var e = assertThrows(IllegalStateException.class, () -> task(Invoke.InvocationType.SYNC).run(runContext()));

        assertThat(e.getMessage(), containsString("UnhandledInvocationError"));
        assertThat(e.getMessage(), containsString("boom"));
        assertThat(e.getMessage(), containsString("check the function logs for request ID req-5"));
    }

    @Test
    void failsOnForbidden() {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"Code\":\"AccessDenied\",\"Message\":\"not authorized\",\"RequestId\":\"req-4\"}")));

        var e = assertThrows(IllegalStateException.class, () -> task(Invoke.InvocationType.SYNC).run(runContext()));

        assertThat(e.getMessage(), containsString("403"));
        assertThat(e.getMessage(), containsString("fc:InvokeFunction"));
    }

    @Test
    void rejectsInvalidRegion() {
        var task = regionTask("x.attacker.com/", "1234567890");

        var e = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));

        assertThat(e.getMessage(), containsString("`region` must only contain"));
    }

    @Test
    void rejectsInvalidAccountId() {
        var task = regionTask("cn-hangzhou", "attacker.com/");

        var e = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));

        assertThat(e.getMessage(), containsString("`accountId` must only contain digits"));
    }

    @Test
    void requiresRegionOrEndpoint() {
        var task = regionTask(null, "1234567890");

        var e = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));

        assertThat(e.getMessage(), is("Either `region` or `endpointOverride` must be set"));
    }

    @Test
    void rejectsNonHttpEndpoint() {
        var task = Invoke.builder()
            .id(IdUtils.create())
            .type(Invoke.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue("ftp://127.0.0.1"))
            .functionName(Property.ofValue("resize-image"))
            .build();

        var e = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));

        assertThat(e.getMessage(), containsString("must be an http or https URL"));
    }

    private static Invoke regionTask(String region, String accountId) {
        return Invoke.builder()
            .id(IdUtils.create())
            .type(Invoke.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(region == null ? null : Property.ofValue(region))
            .accountId(Property.ofValue(accountId))
            .functionName(Property.ofValue("resize-image"))
            .build();
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of());
    }

    // deserialized like a flow is, so the payload goes through the real render path
    private static Invoke payloadTask(Object payload) {
        var task = new HashMap<String, Object>();
        task.put("id", IdUtils.create());
        task.put("type", Invoke.class.getName());
        task.put("accessKeyId", "test-id");
        task.put("accessKeySecret", "test-secret");
        task.put("endpointOverride", "http://127.0.0.1:" + wireMock.port());
        task.put("functionName", "resize-image");
        task.put("payload", payload);
        return JacksonMapper.ofJson().convertValue(task, Invoke.class);
    }

    private static Invoke task(Invoke.InvocationType type) {
        return Invoke.builder()
            .id(IdUtils.create())
            .type(Invoke.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue("http://127.0.0.1:" + wireMock.port()))
            .functionName(Property.ofValue("resize-image"))
            .payload(Property.ofValue(Map.of("key", "landing/photo.png")))
            .invocationType(Property.ofValue(type))
            .build();
    }
}
