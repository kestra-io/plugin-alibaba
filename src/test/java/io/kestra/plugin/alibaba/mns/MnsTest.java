package io.kestra.plugin.alibaba.mns;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.alibaba.mns.model.SerdeType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class MnsTest {
    private static final String PATH = "/queues/orders/messages";

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
    void publishInlineMessages() throws Exception {
        stubPublishOk();

        var output = Publish.builder()
            .id(IdUtils.create())
            .type(Publish.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .securityToken(Property.ofValue("test-token"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpointOverride(Property.ofValue(endpoint()))
            .queue(Property.ofValue("orders"))
            .from(List.of(
                Map.of("data", "{{ 'hello' | upper }}"),
                Map.of("data", Map.of("orderId", 42), "delaySeconds", 60, "priority", 1)
            ))
            .build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getMessagesCount(), is(2));
        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH))
            .withHeader("security-token", equalTo("test-token"))
            .withRequestBody(containing("<MessageBody>HELLO</MessageBody>"))
            .withRequestBody(containing("<MessageBody>{\"orderId\":42}</MessageBody>"))
            .withRequestBody(containing("<DelaySeconds>60</DelaySeconds>"))
            .withRequestBody(containing("<Priority>1</Priority>")));
    }

    @Test
    void publishFromInternalStorageFile() throws Exception {
        stubPublishOk();

        var runContext = runContextFactory.of(Map.of());
        var file = runContext.workingDir().createTempFile(".ion").toFile();
        try (var output = new FileOutputStream(file)) {
            for (var i = 0; i < 20; i++) {
                FileSerde.write(output, Map.of("data", "message-" + i));
            }
        }
        var uri = runContext.storage().putFile(file);

        var output = publish(Property.ofValue("cn-hangzhou"), uri.toString()).run(runContext);

        assertThat(output.getMessagesCount(), is(20));
        // 20 messages are sent as a batch of 16 and a batch of 4
        wireMock.verify(2, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void publishFailsOnAccessDenied() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(error(403, "AccessDenied")));

        var e = assertThrows(IllegalStateException.class, () -> publish(Property.ofValue("cn-hangzhou"), List.of(Map.of("data", "hello"))).run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("Unable to publish to MNS queue 'orders' (AccessDenied)"));
        assertThat(e.getMessage(), containsString("mns:BatchSendMessage"));
    }

    @Test
    void consumeStoresAndDeletesMessages() throws Exception {
        stubReceive(messagesXml("{\"orderId\":1}", "{\"orderId\":2}"));
        wireMock.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(204)));

        var runContext = runContextFactory.of(Map.of());
        var output = consume().serdeType(Property.ofValue(SerdeType.JSON)).maxRecords(Property.ofValue(10)).build().run(runContext);

        assertThat(output.getCount(), is(2));
        try (var reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri())))) {
            var rows = FileSerde.readAll(reader).collectList().block();
            assertThat(rows, hasSize(2));
            assertThat(((Map<?, ?>) rows.getFirst()).get("orderId"), is(1));
        }
        wireMock.verify(deleteRequestedFor(urlPathEqualTo(PATH))
            .withRequestBody(containing("handle-0"))
            .withRequestBody(containing("handle-1")));
    }

    @Test
    void consumeKeepsMessagesWithoutAutoDelete() throws Exception {
        stubReceive(messagesXml("hello"));

        var output = consume().autoDelete(Property.ofValue(false)).maxDuration(Property.ofValue(Duration.ofSeconds(10))).build().run(runContextFactory.of(Map.of()));

        assertThat(output.getCount(), is(1));
        wireMock.verify(0, deleteRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void consumeRequiresABound() {
        var e = assertThrows(IllegalArgumentException.class, () -> consume().build().run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("`maxRecords` or `maxDuration` must be set"));
    }

    @Test
    void triggerStartsAnExecutionWithMessages() throws Exception {
        stubReceive(messagesXml("hello"));
        wireMock.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(204)));

        var trigger = Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpointOverride(Property.ofValue(endpoint()))
            .queue(Property.ofValue("orders"))
            .maxRecords(Property.ofValue(10))
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        var execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));
        assertThat(execution.get().getTrigger().getVariables().get("count"), is(1));
    }

    @Test
    void triggerSkipsEmptyQueue() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(error(404, "MessageNotExist")));

        var trigger = Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpointOverride(Property.ofValue(endpoint()))
            .queue(Property.ofValue("orders"))
            .maxRecords(Property.ofValue(10))
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
    }

    @Test
    void realtimeTriggerEmitsOneMessageEach() {
        stubReceive(messagesXml("{\"orderId\":1}", "{\"orderId\":2}"));
        wireMock.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(204)));

        var trigger = RealtimeTrigger.builder()
            .id(IdUtils.create())
            .type(RealtimeTrigger.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpointOverride(Property.ofValue(endpoint()))
            .queue(Property.ofValue("orders"))
            .serdeType(Property.ofValue(SerdeType.JSON))
            .waitTime(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        try {
            var messages = trigger.publisher(trigger.consumeTask().build(), runContextFactory.of(Map.of()))
                .subscribeOn(Schedulers.boundedElastic())
                .take(2)
                .collectList()
                .block(Duration.ofSeconds(30));

            assertThat(messages, hasSize(2));
            assertThat(((Map<?, ?>) messages.get(1).getData()).get("orderId"), is(2));
        } finally {
            trigger.kill();
        }
        wireMock.verify(deleteRequestedFor(urlPathEqualTo(PATH)).withRequestBody(containing("handle-1")));
    }

    @Test
    void realtimeTriggerStopsOnAccessDenied() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(error(403, "AccessDenied")));

        var trigger = RealtimeTrigger.builder()
            .id(IdUtils.create())
            .type(RealtimeTrigger.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpointOverride(Property.ofValue(endpoint()))
            .queue(Property.ofValue("orders"))
            .build();

        var flux = trigger.publisher(trigger.consumeTask().build(), runContextFactory.of(Map.of()))
            .subscribeOn(Schedulers.boundedElastic());

        var e = assertThrows(IllegalStateException.class, () -> flux.blockLast(Duration.ofSeconds(30)));
        assertThat(e.getMessage(), containsString("AccessDenied"));
    }

    @Test
    void secretIsNeverLoggedBySdk() throws Exception {
        stubPublishOk();
        var sdkLogger = (Logger) LoggerFactory.getLogger("com.aliyun.mns");
        var previousLevel = sdkLogger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        sdkLogger.addAppender(appender);
        sdkLogger.setLevel(Level.DEBUG);

        try {
            Publish.builder()
                .id(IdUtils.create())
                .type(Publish.class.getName())
                .accessKeyId(Property.ofValue("test-id"))
                .accessKeySecret(Property.ofValue("very-secret-value"))
                .securityToken(Property.ofValue("very-secret-token"))
                .region(Property.ofValue("cn-hangzhou"))
                .endpointOverride(Property.ofValue(endpoint()))
                .queue(Property.ofValue("orders"))
                .from(List.of(Map.of("data", "hello")))
                .build()
                .run(runContextFactory.of(Map.of()));
        } finally {
            sdkLogger.detachAppender(appender);
            sdkLogger.setLevel(previousLevel);
        }

        assertThat(appender.list.isEmpty(), is(false));
        for (var event : appender.list) {
            assertThat(event.getFormattedMessage(), not(containsString("very-secret-value")));
            assertThat(event.getFormattedMessage(), not(containsString("very-secret-token")));
        }
    }

    @Test
    void rejectsInvalidRegion() {
        var e = assertThrows(IllegalArgumentException.class, () -> publish(Property.ofValue("x.attacker.com/"), List.of(Map.of("data", "hello"))).run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("`region` must only contain"));
    }

    @Test
    void requiresRegion() {
        var e = assertThrows(IllegalArgumentException.class, () -> publish(null, List.of(Map.of("data", "hello"))).run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), is("`region` is required"));
    }

    @Test
    void rejectsInvalidAccountId() {
        var task = Publish.builder()
            .id(IdUtils.create())
            .type(Publish.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(Property.ofValue("cn-hangzhou"))
            .accountId(Property.ofValue("attacker.com/"))
            .queue(Property.ofValue("orders"))
            .from(List.of(Map.of("data", "hello")))
            .build();

        var e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("`accountId` must only contain digits"));
    }

    @Test
    void rejectsNonHttpEndpoint() {
        var task = Publish.builder()
            .id(IdUtils.create())
            .type(Publish.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpointOverride(Property.ofValue("ftp://127.0.0.1"))
            .queue(Property.ofValue("orders"))
            .from(List.of(Map.of("data", "hello")))
            .build();

        var e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("must be an http or https URL"));
    }

    private Publish publish(Property<String> region, Object from) {
        return Publish.builder()
            .id(IdUtils.create())
            .type(Publish.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(region)
            .endpointOverride(Property.ofValue(endpoint()))
            .queue(Property.ofValue("orders"))
            .from(from)
            .build();
    }

    private Consume.ConsumeBuilder<?, ?> consume() {
        return Consume.builder()
            .id(IdUtils.create())
            .type(Consume.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpointOverride(Property.ofValue(endpoint()))
            .queue(Property.ofValue("orders"));
    }

    private static String endpoint() {
        return "http://127.0.0.1:" + wireMock.port();
    }

    private static void stubPublishOk() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse()
            .withStatus(201)
            .withHeader("Content-Type", "text/xml")
            .withBody("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Messages xmlns="http://mns.aliyuncs.com/doc/v1">
                  <Message><MessageId>id-0</MessageId><MessageBodyMD5>00</MessageBodyMD5></Message>
                </Messages>
                """)));
    }

    // first receive returns the messages, the following ones report an empty queue
    private static void stubReceive(String body) {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("receive").whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml").withBody(body))
            .willSetStateTo("drained"));
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("receive").whenScenarioStateIs("drained")
            .willReturn(error(404, "MessageNotExist")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder error(int status, String code) {
        return aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "text/xml")
            .withBody("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Error xmlns="http://mns.aliyuncs.com/doc/v1">
                  <Code>%s</Code>
                  <Message>%s</Message>
                  <RequestId>req-1</RequestId>
                  <HostId>http://127.0.0.1</HostId>
                </Error>
                """.formatted(code, code));
    }

    private static String messagesXml(String... bodies) {
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Messages xmlns=\"http://mns.aliyuncs.com/doc/v1\">");
        for (var i = 0; i < bodies.length; i++) {
            xml.append("<Message>")
                .append("<MessageId>id-").append(i).append("</MessageId>")
                .append("<ReceiptHandle>handle-").append(i).append("</ReceiptHandle>")
                .append("<MessageBodyMD5>").append(md5(bodies[i])).append("</MessageBodyMD5>")
                .append("<MessageBody>").append(bodies[i].replace("&", "&amp;").replace("<", "&lt;")).append("</MessageBody>")
                .append("<EnqueueTime>1700000000000</EnqueueTime><NextVisibleTime>1700000030000</NextVisibleTime>")
                .append("<FirstDequeueTime>1700000000000</FirstDequeueTime><DequeueCount>1</DequeueCount><Priority>8</Priority>")
                .append("</Message>");
        }
        return xml.append("</Messages>").toString();
    }

    private static String md5(String value) {
        try {
            var digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return String.format("%032X", new BigInteger(1, digest));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
