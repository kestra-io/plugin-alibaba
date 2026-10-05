package io.kestra.plugin.alibaba.fc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        Invoke.Output output = task(Invoke.InvocationType.SYNC).run(runContext());

        assertThat(output.getStatusCode(), is(200));
        assertThat(output.getBody(), is("{\"resized\":true}"));
        assertThat(output.getRequestId(), is("req-1"));
        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH))
            .withHeader("x-fc-invocation-type", equalTo("Sync"))
            .withRequestBody(equalToJson("{\"key\":\"landing/photo.png\"}")));
    }

    @Test
    void async() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(aResponse().withStatus(202).withHeader("x-fc-request-id", "req-2")));

        Invoke.Output output = task(Invoke.InvocationType.ASYNC).run(runContext());

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

        Exception e = assertThrows(Exception.class, () -> task(Invoke.InvocationType.SYNC).run(runContext()));

        assertThat(e.getMessage(), containsString("resize-image"));
        assertThat(e.getMessage(), containsString("404"));
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
