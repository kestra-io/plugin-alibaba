package io.kestra.plugin.alibaba.oss;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "Trigger a flow when objects appear in an OSS bucket",
    description = "Polls a bucket on a fixed interval, downloads the matching objects into Kestra internal storage and starts one execution with all of them. Each downloaded object is then deleted or moved so that it is not picked up again. Objects are downloaded first, and the execution is started even if the delete or move of some of them fails: those objects are reported again by the next poll. An object that cannot be downloaded is left in place and skipped."
)
@Plugin(
    examples = {
        @Example(
            title = "Process the CSV files of a prefix and move them to an archive prefix",
            full = true,
            code = """
                id: oss_trigger
                namespace: company.team

                tasks:
                  - id: each
                    type: io.kestra.plugin.core.flow.ForEach
                    values: "{{ trigger.objects | jq('.[].uri') }}"
                    tasks:
                      - id: log
                        type: io.kestra.plugin.core.log.Log
                        message: "{{ taskrun.value }}"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.alibaba.oss.Trigger
                    interval: PT1M
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    bucket: my-bucket
                    prefix: landing/
                    regexp: ".*\\\\.csv"
                    action: MOVE
                    moveToPrefix: archive/
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Downloads.Output> {
    @Schema(title = "Polling interval")
    @PluginProperty(group = "execution")
    @Builder.Default
    private final Duration interval = Duration.ofSeconds(60);

    @Schema(title = "AccessKey ID", description = "The AccessKey ID used to authenticate against Alibaba Cloud.")
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> accessKeyId;

    @Schema(title = "AccessKey secret", description = "The AccessKey secret paired with the AccessKey ID.")
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> accessKeySecret;

    @Schema(title = "Security token", description = "Optional Security Token Service (STS) token, required when using temporary credentials.")
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> securityToken;

    @Schema(title = "Region", description = "The Alibaba Cloud region ID, for example `cn-hangzhou`. The service endpoint is derived from it unless `endpointOverride` is set.")
    @PluginProperty(group = "connection")
    protected Property<String> region;

    @Schema(title = "Endpoint override", description = "Custom http or https endpoint URL. When set, it takes precedence over the endpoint derived from `region`.")
    @PluginProperty(group = "connection")
    protected Property<String> endpointOverride;

    @Schema(title = "Bucket", description = "The name of the OSS bucket to poll.")
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> bucket;

    @Schema(
        title = "Path-style access",
        description = "Address buckets as `<endpoint>/<bucket>` instead of `<bucket>.<endpoint>`. Useful with custom endpoints such as a local emulator."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    protected Property<Boolean> pathStyleAccess = Property.ofValue(false);

    @Schema(title = "Prefix", description = "Only objects whose key starts with this prefix are picked up.")
    @PluginProperty(group = "source")
    private Property<String> prefix;

    @Schema(title = "Regular expression", description = "Only objects whose full key matches this regular expression are picked up.")
    @PluginProperty(group = "advanced")
    private Property<String> regexp;

    @Schema(title = "Maximum number of files", description = "Maximum number of objects processed per poll, between 1 and 1000. The rest is picked up by the next polls.")
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Integer> maxFiles = Property.ofValue(25);

    @Schema(
        title = "Action",
        description = "What to do with each object once it is downloaded: `DELETE` removes it, `MOVE` copies it under `moveToPrefix` and removes the original."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Downloads.Action> action;

    @Schema(
        title = "Move destination bucket",
        description = "The bucket objects are moved to when `action` is `MOVE`. Defaults to `bucket`."
    )
    @PluginProperty(group = "destination")
    private Property<String> moveToBucket;

    @Schema(
        title = "Move destination prefix",
        description = "Prefix prepended to the original key when `action` is `MOVE`. Required for `MOVE`. The task fails when a moved key in the same bucket would still match `prefix` and `regexp`, because such objects would be picked up again."
    )
    @PluginProperty(group = "destination")
    private Property<String> moveToPrefix;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();

        var rAction = runContext.render(this.action).as(Downloads.Action.class)
            .orElseThrow(() -> new IllegalArgumentException("`action` is required"));
        if (rAction == Downloads.Action.NONE) {
            throw new IllegalArgumentException("`action` must be DELETE or MOVE, otherwise the same objects would start an execution on every poll");
        }

        var output = Downloads.builder()
            .id(this.getId())
            .type(Downloads.class.getName())
            .accessKeyId(this.accessKeyId)
            .accessKeySecret(this.accessKeySecret)
            .securityToken(this.securityToken)
            .region(this.region)
            .endpointOverride(this.endpointOverride)
            .pathStyleAccess(this.pathStyleAccess)
            .bucket(this.bucket)
            .prefix(this.prefix)
            .regexp(this.regexp)
            .maxFiles(this.maxFiles)
            .action(this.action)
            .moveToBucket(this.moveToBucket)
            .moveToPrefix(this.moveToPrefix)
            .build()
            .run(runContext);

        if (output.getObjects().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }
}
