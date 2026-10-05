package io.kestra.plugin.alibaba.sls;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.alibaba.AlibabaConnectionInterface;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on new logs in Alibaba Cloud Simple Log Service (SLS)",
    description = "Periodically polls an SLS Logstore for new log records matching a query. Maintains an epoch cursor in the namespace KV store to guarantee exactly-once triggering without duplicate events."
)
@Plugin(
    examples = {
        @Example(
            title = "React to error logs in SLS",
            full = true,
            code = """
                id: sls_on_errors
                namespace: company.team

                triggers:
                  - id: on_errors
                    type: io.kestra.plugin.alibaba.sls.Trigger
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    project: ops-logs
                    logstore: app-events
                    query: "level: ERROR"
                    interval: PT1M

                tasks:
                  - id: alert
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.count }} error logs received"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Query.Output>, AlibabaConnectionInterface {
    @Builder.Default
    @Schema(title = "Polling interval", description = "Interval between query runs; defaults to 60 seconds.")
    @PluginProperty(group = "execution")
    private final Duration interval = Duration.ofSeconds(60);

    @NotNull
    @Schema(title = "The AccessKey ID")
    @PluginProperty(group = "connection")
    protected Property<String> accessKeyId;

    @NotNull
    @Schema(title = "The AccessKey Secret")
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> accessKeySecret;

    @Schema(title = "Security Token")
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> securityToken;

    @NotNull
    @Schema(title = "Region")
    @PluginProperty(group = "connection")
    protected Property<String> region;

    @Schema(title = "Endpoint override")
    @PluginProperty(group = "connection")
    protected Property<String> endpoint;

    @NotNull
    @Schema(title = "Project", description = "The SLS project name.")
    @PluginProperty(group = "main")
    private Property<String> project;

    @NotNull
    @Schema(title = "Logstore", description = "The SLS Logstore name.")
    @PluginProperty(group = "main")
    private Property<String> logstore;

    @Builder.Default
    @Schema(title = "Query", description = "Query to filter logs; defaults to empty.")
    @PluginProperty(group = "main")
    private Property<String> query = Property.ofValue("");

    @Builder.Default
    @Schema(title = "Topic", description = "Topic filter.")
    @PluginProperty(group = "advanced")
    private Property<String> topic = Property.ofValue("");

    @Builder.Default
    @Schema(title = "Initial lookback duration", description = "Lookback duration when no prior cursor exists in the KV store; defaults to 5 minutes.")
    @PluginProperty(group = "processing")
    private Property<Duration> lookback = Property.ofValue(Duration.ofMinutes(5));

    @Builder.Default
    @Schema(title = "Result handling mode", description = "FETCH or STORE; defaults to FETCH.")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        String rNamespace = context.getNamespace();
        String cursorKey = StatefulTriggerService.defaultKey(rNamespace, context.getFlowId(), this.getId()) + "_cursor";

        Duration rLookback = runContext.render(this.lookback).as(Duration.class).orElse(Duration.ofMinutes(5));
        int nowEpoch = (int) Instant.now().getEpochSecond();
        int fromEpoch = nowEpoch - (int) rLookback.getSeconds();

        try {
            var kvOptional = runContext.namespaceKv(rNamespace).getValue(cursorKey);
            if (kvOptional.isPresent() && kvOptional.get().value() != null) {
                String strVal = new String((byte[]) kvOptional.get().value(), StandardCharsets.UTF_8);
                fromEpoch = Integer.parseInt(strVal);
            }
        } catch (Exception e) {
            runContext.logger().warn("Could not read cursor from KV store", e);
        }

        if (fromEpoch >= nowEpoch) {
            return Optional.empty();
        }

        Query queryTask = Query.builder()
            .id(this.getId())
            .type(Query.class.getName())
            .accessKeyId(this.accessKeyId)
            .accessKeySecret(this.accessKeySecret)
            .securityToken(this.securityToken)
            .region(this.region)
            .endpoint(this.endpoint)
            .project(this.project)
            .logstore(this.logstore)
            .query(this.query)
            .topic(this.topic)
            .from(Property.ofValue(String.valueOf(fromEpoch)))
            .to(Property.ofValue(String.valueOf(nowEpoch)))
            .fetchType(this.fetchType)
            .build();

        Query.Output output = queryTask.run(runContext);

        // Advance cursor to nowEpoch to prevent reprocessing
        try {
            runContext.namespaceKv(rNamespace).put(
                cursorKey,
                new KVValueAndMetadata(new KVMetadata("trigger cursor", (Duration) null), String.valueOf(nowEpoch).getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            runContext.logger().warn("Could not persist cursor to KV store", e);
        }

        if (output.getCount() == null || output.getCount() == 0) {
            return Optional.empty();
        }

        Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
        return Optional.of(execution);
    }
}
