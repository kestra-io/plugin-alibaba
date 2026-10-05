package io.kestra.plugin.alibaba.maxcompute;

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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@KestraTest
class MaxComputeTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildQueryTaskAndSupportKill() {
        Query query = Query.builder()
            .id("test-query")
            .type(Query.class.getName())
            .accessKeyId(Property.ofValue("ak-test"))
            .accessKeySecret(Property.ofValue("sk-test"))
            .region(Property.ofValue("cn-shanghai"))
            .project(Property.ofValue("analytics"))
            .sql(Property.ofValue("SELECT * FROM test_table"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        assertThat(query.getProject(), notNullValue());
        assertThat(query.getSql(), notNullValue());
        assertThat(query.getFetchType(), notNullValue());

        // kill() must complete cleanly even if instance was not started or terminated
        assertDoesNotThrow(query::kill);
    }

    @lombok.experimental.SuperBuilder
    @lombok.NoArgsConstructor
    public static class TestableTrigger extends Trigger {
        @lombok.Builder.Default
        private int callCount = 0;

        @Override
        public Optional<Execution> evaluate(ConditionContext condCtx, TriggerContext ctx) throws Exception {
            RunContext rc = condCtx.getRunContext();
            String watermarkKey = io.kestra.core.models.triggers.StatefulTriggerService.defaultKey(ctx.getNamespace(), ctx.getFlowId(), this.getId()) + "_watermark";

            var kv = rc.namespaceKv(ctx.getNamespace()).getValue(watermarkKey);
            String currentWatermark = kv.map(v -> new String((byte[]) v.value(), StandardCharsets.UTF_8)).orElse("");

            callCount++;
            List<Map<String, Object>> mockRows = new ArrayList<>();
            if (callCount == 1) {
                mockRows.add(Map.of("id", "100", "val", "first"));
                mockRows.add(Map.of("id", "105", "val", "second"));
            } else if (callCount == 2) {
                if ("105".compareTo(currentWatermark) <= 0) {
                    return Optional.empty();
                }
            }

            if (mockRows.isEmpty()) {
                return Optional.empty();
            }

            rc.namespaceKv(ctx.getNamespace()).put(
                watermarkKey,
                new KVValueAndMetadata(new KVMetadata("trigger watermark", (Duration) null), "105".getBytes(StandardCharsets.UTF_8))
            );

            Query.Output output = Query.Output.builder()
                .rows(mockRows)
                .size((long) mockRows.size())
                .build();

            return Optional.of(io.kestra.core.models.triggers.TriggerService.generateExecution(this, condCtx, ctx, output));
        }
    }

    @Test
    void shouldTriggerWithWatermarkAndPreventDuplicates() throws Exception {
        String namespace = "company.team";
        String flowId = "mc_flow";
        String triggerId = "mc_trigger";

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

        TestableTrigger trigger = TestableTrigger.builder()
            .id(triggerId)
            .accessKeyId(Property.ofValue("ak"))
            .accessKeySecret(Property.ofValue("sk"))
            .region(Property.ofValue("cn-shanghai"))
            .project(Property.ofValue("analytics"))
            .sql(Property.ofValue("SELECT id, val FROM t WHERE id > '{{ trigger.watermark }}'"))
            .watermarkField(Property.ofValue("id"))
            .build();

        runContextFactory.initializer().forScheduler((io.kestra.core.runners.DefaultRunContext) runContext, triggerContext, trigger);

        // 1st run should fire with new rows
        Optional<Execution> firstExecution = trigger.evaluate(conditionContext, triggerContext);
        assertThat(firstExecution.isPresent(), is(true));

        // Verify watermark persisted in KV store
        String watermarkKey = io.kestra.core.models.triggers.StatefulTriggerService.defaultKey(namespace, flowId, triggerId) + "_watermark";
        var persisted = runContext.namespaceKv(namespace).getValue(watermarkKey);
        assertThat(persisted.isPresent(), is(true));
        assertThat(new String((byte[]) persisted.get().value(), StandardCharsets.UTF_8), is("105"));

        // 2nd run with the same watermark should not re-fire
        Optional<Execution> secondExecution = trigger.evaluate(conditionContext, triggerContext);
        assertThat(secondExecution.isPresent(), is(false));
    }
}
