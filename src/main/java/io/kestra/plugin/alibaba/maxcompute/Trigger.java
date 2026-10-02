package io.kestra.plugin.alibaba.maxcompute;

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
import java.util.*;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on MaxCompute query results",
    description = "Periodically executes a MaxCompute SQL query and triggers a flow execution when rows are returned. Supports persisting watermarks in the namespace KV store to prevent duplicate processing."
)
@Plugin(
    examples = {
        @Example(
            title = "Poll for new records in MaxCompute",
            full = true,
            code = """
                id: maxcompute_listen
                namespace: company.team

                tasks:
                  - id: log_rows
                    type: io.kestra.plugin.core.log.Log
                    message: "Found {{ trigger.size }} new rows"

                triggers:
                  - id: watch_orders
                    type: io.kestra.plugin.alibaba.maxcompute.Trigger
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-shanghai
                    project: analytics
                    sql: "SELECT * FROM orders WHERE id > '{{ trigger.watermark }}' ORDER BY id ASC LIMIT 50"
                    watermarkField: id
                    interval: PT5M
                    fetchType: FETCH
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Query.Output>, AlibabaConnectionInterface {
    @Builder.Default
    @Schema(
        title = "Polling interval",
        description = "Interval between query runs; defaults to 60 seconds."
    )
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
    @Schema(title = "Project", description = "MaxCompute project name.")
    @PluginProperty(group = "main")
    private Property<String> project;

    @NotNull
    @Schema(title = "SQL query", description = "Query executed on each poll. May reference {{ trigger.watermark }}.")
    @PluginProperty(group = "main")
    private Property<String> sql;

    @Schema(title = "Watermark column", description = "Column name to track as watermark in namespace KV store.")
    @PluginProperty(group = "processing")
    private Property<String> watermarkField;

    @Builder.Default
    @Schema(title = "Result handling mode", description = "FETCH or STORE; defaults to FETCH.")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Tunnel endpoint override")
    @PluginProperty(group = "advanced")
    private Property<String> tunnelEndpoint;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        String rNamespace = context.getNamespace();
        String watermarkKey = StatefulTriggerService.defaultKey(rNamespace, context.getFlowId(), this.getId()) + "_watermark";

        String previousWatermark = "";
        try {
            var kvOptional = runContext.namespaceKv(rNamespace).getValue(watermarkKey);
            if (kvOptional.isPresent() && kvOptional.get().value() != null) {
                previousWatermark = new String((byte[]) kvOptional.get().value(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            runContext.logger().warn("Could not read watermark from KV store", e);
        }

        Map<String, Object> variables = Map.of(
            "watermark", previousWatermark,
            "trigger", Map.of("watermark", previousWatermark)
        );

        String renderedSql = runContext.render(this.sql).as(String.class, variables).orElseThrow();

        Query query = Query.builder()
            .id(this.getId())
            .type(Query.class.getName())
            .accessKeyId(this.accessKeyId)
            .accessKeySecret(this.accessKeySecret)
            .securityToken(this.securityToken)
            .region(this.region)
            .endpoint(this.endpoint)
            .project(this.project)
            .sql(Property.ofValue(renderedSql))
            .fetchType(this.fetchType)
            .tunnelEndpoint(this.tunnelEndpoint)
            .build();

        Query.Output output = query.run(runContext);

        if (output.getSize() == null || output.getSize() == 0) {
            return Optional.empty();
        }

        String rWatermarkField = runContext.render(this.watermarkField).as(String.class).orElse(null);
        if (rWatermarkField != null && output.getRows() != null && !output.getRows().isEmpty()) {
            String maxWatermark = previousWatermark;
            for (Map<String, Object> row : output.getRows()) {
                Object val = row.get(rWatermarkField);
                if (val != null) {
                    String strVal = String.valueOf(val);
                    if (maxWatermark.isEmpty() || strVal.compareTo(maxWatermark) > 0) {
                        maxWatermark = strVal;
                    }
                }
            }

            if (!maxWatermark.equals(previousWatermark)) {
                try {
                    runContext.namespaceKv(rNamespace).put(
                        watermarkKey,
                        new KVValueAndMetadata(new KVMetadata("trigger watermark", (Duration) null), maxWatermark.getBytes(StandardCharsets.UTF_8))
                    );
                } catch (Exception e) {
                    runContext.logger().warn("Could not persist watermark to KV store", e);
                }
            }
        }

        Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
        return Optional.of(execution);
    }
}
