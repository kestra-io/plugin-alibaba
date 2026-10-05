package io.kestra.plugin.alibaba.mns;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow on MNS queue messages",
    description = "Polls an MNS queue on an interval and starts one execution per poll that received messages. The messages are stored in an ION file available as `trigger.uri`, and their number as `trigger.count`. Use `RealtimeTrigger` for one execution per message."
)
@Plugin(
    examples = {
        @Example(
            title = "Process up to 100 queued orders every 30 seconds",
            full = true,
            code = """
                id: mns_on_message
                namespace: company.team

                triggers:
                  - id: on_message
                    type: io.kestra.plugin.alibaba.mns.Trigger
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    accountId: "{{ secret('ALIBABA_ACCOUNT_ID') }}"
                    queue: orders
                    interval: PT30S
                    maxRecords: 100

                tasks:
                  - id: handle_messages
                    type: io.kestra.plugin.core.log.Log
                    message: "Received {{ trigger.count }} messages"
                """
        )
    }
)
public class Trigger extends AbstractMnsTrigger implements PollingTriggerInterface, TriggerOutput<Consume.Output> {
    @Schema(title = "Interval between polls")
    @PluginProperty(group = "execution")
    @Builder.Default
    private final Duration interval = Duration.ofSeconds(60);

    @Schema(
        title = "Max records",
        description = "Stop a poll after consuming this many messages. At least one of `maxRecords` and `maxDuration` is required."
    )
    @PluginProperty(group = "execution")
    private Property<Integer> maxRecords;

    @Schema(
        title = "Max duration",
        description = "Stop a poll after this duration elapses. At least one of `maxRecords` and `maxDuration` is required."
    )
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();

        var output = consumeTask()
            .maxRecords(this.maxRecords)
            .maxDuration(this.maxDuration)
            .build()
            .run(runContext);

        if (output.getCount() == 0) {
            return Optional.empty();
        }
        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }
}
