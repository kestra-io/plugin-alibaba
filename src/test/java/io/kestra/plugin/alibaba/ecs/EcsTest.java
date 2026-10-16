package io.kestra.plugin.alibaba.ecs;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.alibaba.AbstractConnection;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class EcsTest {
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
    void listFetchesAllPages() throws Exception {
        stubAction("DescribeInstances", absent(), 200, describeBody("page-2", "i-1", "web-1", "Running"));
        stubAction("DescribeInstances", equalTo("page-2"), 200, describeBody("", "i-2", "web-2", "Stopped"));

        var output = connected(List.builder(), List.class)
            .status(Property.ofValue(List.Status.RUNNING))
            .build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getSize(), is(2L));
        assertThat(output.getRows(), hasSize(2));
        assertThat(((Map<?, ?>) output.getRows().get(0)).get("InstanceId"), is("i-1"));
        assertThat(((Map<?, ?>) output.getRows().get(1)).get("Status"), is("Stopped"));
        wireMock.verify(anyRequestedFor(urlPathEqualTo("/"))
            .withHeader("x-acs-action", equalTo("DescribeInstances"))
            .withQueryParam("RegionId", equalTo("cn-hangzhou"))
            .withQueryParam("Status", equalTo("Running")));
    }

    @Test
    void listStoresToInternalStorage() throws Exception {
        stubAction("DescribeInstances", absent(), 200, describeBody("", "i-1", "web-1", "Running"));

        var runContext = runContextFactory.of(Map.of());
        var output = connected(List.builder(), List.class)
            .instanceIds(Property.ofValue(java.util.List.of("i-1")))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build()
            .run(runContext);

        assertThat(output.getSize(), is(1L));
        assertThat(output.getRows(), nullValue());
        try (var stream = runContext.storage().getFile(output.getUri())) {
            var rows = new ArrayList<Object>();
            FileSerde.read(stream, rows::add);
            assertThat(rows, hasSize(1));
            assertThat(((Map<?, ?>) rows.getFirst()).get("InstanceId"), is("i-1"));
        }
        wireMock.verify(anyRequestedFor(urlPathEqualTo("/"))
            .withQueryParam("InstanceIds", equalTo("[\"i-1\"]")));
    }

    @Test
    void listFetchOneReportsOneRow() throws Exception {
        stubAction("DescribeInstances", absent(), 200, describeBody("page-2", "i-1", "web-1", "Running"));

        var output = connected(List.builder(), List.class)
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getSize(), is(1L));
        assertThat(((Map<?, ?>) output.getRow()).get("InstanceId"), is("i-1"));
        wireMock.verify(1, anyRequestedFor(urlPathEqualTo("/")));
    }

    @Test
    void listNoneOnlyCounts() throws Exception {
        stubAction("DescribeInstances", absent(), 200, describeBody("page-2", "i-1", "web-1", "Running"));
        stubAction("DescribeInstances", equalTo("page-2"), 200, describeBody("", "i-2", "web-2", "Stopped"));

        var output = connected(List.builder(), List.class)
            .fetchType(Property.ofValue(FetchType.NONE))
            .build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getSize(), is(2L));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void listFiltersByInstanceName() throws Exception {
        stubAction("DescribeInstances", absent(), 200, describeBody("", "i-1", "web-1", "Running"));

        connected(List.builder(), List.class)
            .instanceName(Property.ofValue("web-*"))
            .build()
            .run(runContextFactory.of(Map.of()));

        wireMock.verify(anyRequestedFor(urlPathEqualTo("/"))
            .withQueryParam("InstanceName", equalTo("web-*")));
    }

    @Test
    void listRejectsMoreThan100InstanceIds() {
        var ids = IntStream.rangeClosed(1, 101).mapToObj(i -> "i-" + i).toList();
        var task = connected(List.builder(), List.class)
            .instanceIds(Property.ofValue(ids))
            .build();

        var e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), is("`instanceIds` accepts at most 100 IDs, got 101"));
        wireMock.verify(0, anyRequestedFor(urlPathEqualTo("/")));
    }

    @Test
    void start() throws Exception {
        stubAction("StartInstances", absent(), 200, actionBody("i-1", "200", "Stopped", "Starting"));

        var output = startTask("cn-hangzhou").run(runContextFactory.of(Map.of()));

        assertThat(output.getRequestId(), is("req-1"));
        assertThat(output.getInstances(), hasSize(1));
        assertThat(output.getInstances().getFirst().getInstanceId(), is("i-1"));
        assertThat(output.getInstances().getFirst().getPreviousStatus(), is("Stopped"));
        assertThat(output.getInstances().getFirst().getCurrentStatus(), is("Starting"));
        wireMock.verify(anyRequestedFor(urlPathEqualTo("/"))
            .withHeader("x-acs-action", equalTo("StartInstances"))
            .withQueryParam("InstanceId.1", equalTo("i-1")));
    }

    @Test
    void stop() throws Exception {
        stubAction("StopInstances", absent(), 200, actionBody("i-1", "200", "Running", "Stopping"));

        var output = connected(Stop.builder(), Stop.class)
            .instanceIds(Property.ofValue(java.util.List.of("i-1")))
            .forceStop(Property.ofValue(true))
            .stoppedMode(Property.ofValue(Stop.StoppedMode.STOP_CHARGING))
            .build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getInstances().getFirst().getCurrentStatus(), is("Stopping"));
        wireMock.verify(anyRequestedFor(urlPathEqualTo("/"))
            .withHeader("x-acs-action", equalTo("StopInstances"))
            .withQueryParam("ForceStop", equalTo("true"))
            .withQueryParam("StoppedMode", equalTo("StopCharging")));
    }

    @Test
    void reboot() throws Exception {
        stubAction("RebootInstances", absent(), 200, actionBody("i-1", "200", "Running", "Stopping"));

        var output = connected(Reboot.builder(), Reboot.class)
            .instanceIds(Property.ofValue(java.util.List.of("i-1")))
            .build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getInstances(), hasSize(1));
        wireMock.verify(anyRequestedFor(urlPathEqualTo("/"))
            .withHeader("x-acs-action", equalTo("RebootInstances"))
            .withQueryParam("ForceReboot", equalTo("false")));
    }

    @Test
    void failsOnForbidden() {
        stubAction("StartInstances", absent(), 403,
            "{\"RequestId\":\"req-2\",\"Code\":\"Forbidden.RAM\",\"Message\":\"User not authorized to operate on the specified resource.\"}");

        var e = assertThrows(IllegalStateException.class, () -> startTask("cn-hangzhou").run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("Unable to start instances [i-1]"));
        assertThat(e.getMessage(), containsString("Forbidden.RAM"));
    }

    @Test
    void failsWhenAnInstanceIsRejected() {
        stubAction("StartInstances", absent(), 200, actionBody("i-1", "IncorrectInstanceStatus", "Running", "Running"));

        var e = assertThrows(IllegalStateException.class, () -> startTask("cn-hangzhou").run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("i-1 (IncorrectInstanceStatus"));
        assertThat(e.getMessage(), containsString("Accepted instances: []"));
        assertThat(e.getMessage(), containsString("re-run with only their IDs"));
    }

    @Test
    void failsWithAClearMessageOnReadTimeout() {
        wireMock.stubFor(any(urlPathEqualTo("/")).willReturn(aResponse().withStatus(200).withFixedDelay(3000)
            .withHeader("Content-Type", "application/json").withBody(actionBody("i-1", "200", "Stopped", "Starting"))));
        var task = connected(Start.builder(), Start.class)
            .instanceIds(Property.ofValue(java.util.List.of("i-1")))
            .timeout(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("No response to the start request for instances [i-1] in cn-hangzhou"));
        assertThat(e.getMessage(), containsString("check the instance status before retrying"));
    }

    @Test
    void rejectsInvalidRegion() {
        var e = assertThrows(IllegalArgumentException.class, () -> startTask("x.attacker.com/").run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("`region` must only contain"));
    }

    @Test
    void requiresRegion() {
        var e = assertThrows(IllegalArgumentException.class, () -> startTask(null).run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), is("`region` is required"));
    }

    @Test
    void rejectsNonHttpEndpoint() {
        var task = connected(Start.builder(), Start.class)
            .endpointOverride(Property.ofValue("ftp://127.0.0.1"))
            .instanceIds(Property.ofValue(java.util.List.of("i-1")))
            .build();

        var e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("must be an http or https URL"));
    }

    private Start startTask(String region) {
        return connected(Start.builder(), Start.class)
            .region(region == null ? null : Property.ofValue(region))
            .instanceIds(Property.ofValue(java.util.List.of("i-1")))
            .build();
    }

    private static <B extends AbstractConnection.AbstractConnectionBuilder<?, ?>> B connected(B builder, Class<?> type) {
        builder
            .id(IdUtils.create())
            .type(type.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpointOverride(Property.ofValue("http://127.0.0.1:" + wireMock.port()));
        return builder;
    }

    private static void stubAction(String action, StringValuePattern nextToken, int status, String body) {
        wireMock.stubFor(any(urlPathEqualTo("/"))
            .withHeader("x-acs-action", equalTo(action))
            .withQueryParam("NextToken", nextToken)
            .willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private static String describeBody(String nextToken, String id, String name, String status) {
        return """
            {"RequestId":"req-1","NextToken":"%s","Instances":{"Instance":[{"InstanceId":"%s","InstanceName":"%s","Status":"%s"}]}}
            """.formatted(nextToken, id, name, status);
    }

    private static String actionBody(String id, String code, String previous, String current) {
        return """
            {"RequestId":"req-1","InstanceResponses":{"InstanceResponse":[{"InstanceId":"%s","Code":"%s","Message":"msg","PreviousStatus":"%s","CurrentStatus":"%s"}]}}
            """.formatted(id, code, previous, current);
    }
}
