package io.kestra.plugin.alibaba.ecs;

import com.aliyun.ecs20140526.Client;
import com.aliyun.tea.TeaException;
import com.aliyun.tea.TeaModel;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractInstanceAction extends AbstractEcs implements RunnableTask<AbstractInstanceAction.Output> {
    private static final int MAX_INSTANCES = 100;

    @Schema(
        title = "Instance IDs",
        description = "The IDs of the ECS instances, up to 100 per task run."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<List<String>> instanceIds;

    protected abstract String action();

    protected abstract TeaModel call(RunContext runContext, Client client, String regionId, List<String> ids) throws Exception;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var regionId = rRegion(runContext);
        var ids = runContext.render(this.instanceIds).asList(String.class);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("`instanceIds` is required");
        }
        if (ids.size() > MAX_INSTANCES) {
            throw new IllegalArgumentException("`instanceIds` accepts at most " + MAX_INSTANCES + " IDs, got " + ids.size());
        }

        TeaModel body;
        try {
            body = call(runContext, client(runContext, regionId), regionId, ids);
        } catch (TeaException e) {
            throw new IllegalStateException(
                "Unable to " + action() + " instances " + ids + " in " + regionId + " (" + e.getCode() + ": " + e.getMessage() + "): " +
                    "check that the instances exist in this region, that they are in a state that allows this action, and that the credentials have the required ECS permission",
                e
            );
        }

        var instances = instanceResponses(body).stream()
            .map(r -> Instance.builder()
                .instanceId((String) r.get("InstanceId"))
                .previousStatus((String) r.get("PreviousStatus"))
                .currentStatus((String) r.get("CurrentStatus"))
                .build())
            .toList();

        var failed = instanceResponses(body).stream()
            .filter(r -> r.get("Code") != null && !"200".equals(r.get("Code")))
            .map(r -> r.get("InstanceId") + " (" + r.get("Code") + ": " + r.get("Message") + ")")
            .toList();
        if (!failed.isEmpty()) {
            throw new IllegalStateException("Unable to " + action() + " instances in " + regionId + ": " + String.join(", ", failed));
        }

        runContext.logger().info("Requested {} for {} instance(s) in {}", action(), instances.size(), regionId);

        return Output.builder()
            .requestId((String) body.toMap().get("RequestId"))
            .instances(instances)
            .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> instanceResponses(TeaModel body) {
        var wrapper = (Map<String, Object>) body.toMap().get("InstanceResponses");
        if (wrapper == null || wrapper.get("InstanceResponse") == null) {
            return List.of();
        }
        return (List<Map<String, Object>>) wrapper.get("InstanceResponse");
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "ECS request ID")
        private final String requestId;

        @Schema(title = "Status transition of each instance")
        private final List<Instance> instances;
    }

    @Builder
    @Getter
    public static class Instance {
        @Schema(title = "Instance ID")
        private final String instanceId;

        @Schema(title = "Status before the action")
        private final String previousStatus;

        @Schema(title = "Status right after the action, for example `Starting` or `Stopping`")
        private final String currentStatus;
    }
}
