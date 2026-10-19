package io.kestra.plugin.alibaba.ecs;

import com.aliyun.ecs20140526.Client;
import com.aliyun.tea.TeaException;
import com.aliyun.tea.TeaModel;
import com.aliyun.tea.TeaUnretryableException;
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
import java.util.stream.Collectors;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractInstanceAction extends AbstractEcs implements RunnableTask<AbstractInstanceAction.Output> {
    @Schema(
        title = "Instance IDs",
        description = "The IDs of the ECS instances, up to 100 per task run."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<List<String>> instanceIds;

    protected abstract String action();

    protected abstract TeaModel call(RunContext runContext, Client client, String rRegion, List<String> rInstanceIds) throws Exception;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rRegion = requiredRegion(runContext);
        var rInstanceIds = runContext.render(this.instanceIds).asList(String.class);
        if (rInstanceIds.isEmpty()) {
            throw new IllegalArgumentException("`instanceIds` is required");
        }
        checkInstanceIdCount(rInstanceIds);

        var body = request(runContext, rRegion, rInstanceIds);
        var responses = instanceResponses(body).stream()
            .collect(Collectors.partitioningBy(r -> r.get("Code") == null || "200".equals(r.get("Code"))));

        var failed = responses.get(false);
        if (!failed.isEmpty()) {
            var accepted = responses.get(true).stream().map(r -> (String) r.get("InstanceId")).toList();
            var failures = failed.stream().map(r -> r.get("InstanceId") + " (" + r.get("Code") + ": " + r.get("Message") + ")").toList();
            runContext.logger().info("Requested {} for instance(s) {} in {} before the failure", action(), accepted, rRegion);
            throw new IllegalStateException(
                "Unable to " + action() + " instances in " + rRegion + ": " + String.join(", ", failures) + ". Accepted instances: " + accepted + ". " +
                    "Check the status of the failed instances and re-run with only their IDs, the accepted instances are already being processed"
            );
        }

        var instances = responses.get(true).stream()
            .map(r -> Instance.builder()
                .instanceId((String) r.get("InstanceId"))
                .previousStatus((String) r.get("PreviousStatus"))
                .currentStatus((String) r.get("CurrentStatus"))
                .build())
            .toList();
        runContext.logger().info("Requested {} for {} instance(s) in {}", action(), instances.size(), rRegion);

        return Output.builder()
            .requestId((String) body.toMap().get("RequestId"))
            .instances(instances)
            .build();
    }

    private TeaModel request(RunContext runContext, String rRegion, List<String> rInstanceIds) throws Exception {
        try {
            return call(runContext, client(runContext, rRegion), rRegion, rInstanceIds);
        } catch (TeaException e) {
            throw new IllegalStateException(
                "Unable to " + action() + " instances " + rInstanceIds + " in " + rRegion + " (" + e.getCode() + ": " + e.getMessage() + "): " +
                    "check that the instances exist in this region, that they are in a state that allows this action, and that the credentials have the required ECS permission",
                e
            );
        } catch (TeaUnretryableException e) {
            throw new IllegalStateException(
                "No response to the " + action() + " request for instances " + rInstanceIds + " in " + rRegion + " (" + e.getMessage() + "): " +
                    "the request may already have been accepted, check the instance status before retrying, or raise the task `timeout`",
                e
            );
        }
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
