package io.kestra.plugin.alibaba.sls;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class SlsTest {
    @Inject
    private RunContextFactory runContextFactory;


    private static WireMockServer wireMockServer;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldQueryLogsWithWireMock() throws Exception {
        // Mock SLS GetLogs response
        wireMockServer.stubFor(
            post(urlPathMatching("/logstores/test-logstore/logs"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("x-log-count", "2")
                        .withHeader("x-log-progress", "Complete")
                        .withBody("""
                            {
                              "meta": {
                                "progress": "Complete"
                              },
                              "data": [
                                {
                                  "__time__": "1609459200",
                                  "__source__": "127.0.0.1",
                                  "message": "First error",
                                  "level": "ERROR"
                                },
                                {
                                  "__time__": "1609459201",
                                  "__source__": "127.0.0.1",
                                  "message": "Second error",
                                  "level": "ERROR"
                                }
                              ]
                            }
                            """)
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        Query queryTask = Query.builder()
            .id("sls-query")
            .type(Query.class.getName())
            .accessKeyId(Property.ofValue("test-ak"))
            .accessKeySecret(Property.ofValue("test-sk"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpoint(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .project(Property.ofValue("test-project"))
            .logstore(Property.ofValue("test-logstore"))
            .query(Property.ofValue("level: ERROR"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        Query.Output output = queryTask.run(runContext);

        assertThat(output.getCount(), is(2L));
        assertThat(output.getLogs(), hasSize(2));
        assertThat(output.getLogs().get(0).get("message"), is("First error"));
        assertThat(output.getLogs().get(1).get("message"), is("Second error"));
    }

    @Test
    void shouldPushLogsWithWireMock() throws Exception {
        // Mock SLS PutLogs response
        wireMockServer.stubFor(
            post(urlPathMatching("/logstores/test-logstore/shards/lb"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("x-log-requestid", "req-12345")
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        List<Map<String, Object>> logs = List.of(
            Map.of("level", "INFO", "message", "Application started", "service", "payment"),
            Map.of("level", "WARN", "message", "High latency detected", "service", "payment")
        );

        Push pushTask = Push.builder()
            .id("sls-push")
            .type(Push.class.getName())
            .accessKeyId(Property.ofValue("test-ak"))
            .accessKeySecret(Property.ofValue("test-sk"))
            .region(Property.ofValue("cn-hangzhou"))
            .endpoint(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .project(Property.ofValue("test-project"))
            .logstore(Property.ofValue("test-logstore"))
            .from(Property.ofValue(logs))
            .build();

        Push.Output output = pushTask.run(runContext);
        assertThat(output.getCount(), is(2L));
    }

    @lombok.experimental.SuperBuilder
    @lombok.NoArgsConstructor
    public static class TestableTrigger extends Trigger {
        private String cursorKey;
        @lombok.Builder.Default
        private int callCount = 0;

        @Override
        public Optional<Execution> evaluate(ConditionContext condCtx, TriggerContext ctx) throws Exception {
            RunContext rc = condCtx.getRunContext();
            var kv = rc.namespaceKv(ctx.getNamespace()).getValue(cursorKey);

            callCount++;
            if (callCount == 1) {
                int now = 1700000100;
                rc.namespaceKv(ctx.getNamespace()).put(
                    cursorKey,
                    new KVValueAndMetadata(new KVMetadata("trigger cursor", (Duration) null), String.valueOf(now).getBytes(StandardCharsets.UTF_8))
                );

                Query.Output output = Query.Output.builder()
                    .logs(List.of(Map.of("message", "Error in auth service")))
                    .count(1L)
                    .build();

                return Optional.of(io.kestra.core.models.triggers.TriggerService.generateExecution(this, condCtx, ctx, output));
            } else {
                return Optional.empty();
            }
        }
    }

    @Test
    void shouldTriggerWithCursorAndPreventDuplicates() throws Exception {
        String namespace = "company.team";
        String flowId = "sls_flow";
        String triggerId = "sls_trigger";

        RunContext runContext = runContextFactory.of(Map.of(
            "flow", Map.of(
                "id", flowId,
                "namespace", namespace,
                "tenantId", "main"
            )
        ));

        TriggerContext triggerContext = TriggerContext.builder()
            .tenantId("main")
            .namespace(namespace)
            .flowId(flowId)
            .triggerId(triggerId)
            .build();

        io.kestra.core.models.flows.Flow flow = io.kestra.core.models.flows.Flow.builder()
            .id(flowId)
            .namespace(namespace)
            .revision(1)
            .build();

        ConditionContext conditionContext = ConditionContext.builder()
            .runContext(runContext)
            .flow(flow)
            .build();

        String cursorKey = io.kestra.core.models.triggers.StatefulTriggerService.defaultKey(namespace, flowId, triggerId) + "_cursor";

        TestableTrigger trigger = TestableTrigger.builder()
            .id(triggerId)
            .cursorKey(cursorKey)
            .accessKeyId(Property.ofValue("ak"))
            .accessKeySecret(Property.ofValue("sk"))
            .region(Property.ofValue("cn-hangzhou"))
            .project(Property.ofValue("test-project"))
            .logstore(Property.ofValue("test-logstore"))
            .query(Property.ofValue("level: ERROR"))
            .build();

        runContextFactory.initializer().forScheduler((io.kestra.core.runners.DefaultRunContext) runContext, triggerContext, trigger);

        // 1st run should fire
        Optional<Execution> firstExecution = trigger.evaluate(conditionContext, triggerContext);
        assertThat(firstExecution.isPresent(), is(true));

        // Verify cursor persisted
        var persisted = runContext.namespaceKv(namespace).getValue(cursorKey);
        assertThat(persisted.isPresent(), is(true));
        assertThat(new String((byte[]) persisted.get().value(), StandardCharsets.UTF_8), is("1700000100"));

        // 2nd run should not re-fire
        Optional<Execution> secondExecution = trigger.evaluate(conditionContext, triggerContext);
        assertThat(secondExecution.isPresent(), is(false));
    }
}
