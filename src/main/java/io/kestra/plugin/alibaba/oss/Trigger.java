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

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {
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
    private Property<Action> action;

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
        var logger = runContext.logger();

        var rAction = runContext.render(this.action).as(Action.class)
            .orElseThrow(() -> new IllegalArgumentException("`action` is required"));
        var rBucket = AbstractOss.required(runContext, this.bucket, "bucket");
        String rMoveToPrefix = null;
        if (rAction == Action.MOVE) {
            rMoveToPrefix = AbstractOss.required(runContext, this.moveToPrefix, "moveToPrefix");
        }
        var rMoveToBucket = runContext.render(this.moveToBucket).as(String.class).orElse(rBucket);
        var rPrefix = runContext.render(this.prefix).as(String.class).orElse(null);
        var rRegexp = runContext.render(this.regexp).as(String.class).map(OssRegexp::compile).orElse(null);

        var listed = List.builder()
            .id(this.getId())
            .type(List.class.getName())
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
            .build()
            .run(runContext);

        if (listed.getObjects().isEmpty()) {
            return Optional.empty();
        }

        if (rAction == Action.MOVE && rMoveToBucket.equals(rBucket)) {
            for (var object : listed.getObjects()) {
                var destination = rMoveToPrefix + object.getKey();
                if ((rPrefix == null || destination.startsWith(rPrefix)) && (rRegexp == null || OssRegexp.matches(rRegexp, destination))) {
                    throw new IllegalArgumentException("`moveToPrefix` must lead outside the polled objects, otherwise moved objects are picked up again: oss://" + rBucket + "/" + destination + " still matches `prefix` and `regexp`");
                }
            }
        }

        var downloaded = new ArrayList<Downloaded>();
        var failures = new ArrayList<Exception>();
        for (var object : listed.getObjects()) {
            try {
                var result = Download.builder()
                    .id(this.getId())
                    .type(Download.class.getName())
                    .accessKeyId(this.accessKeyId)
                    .accessKeySecret(this.accessKeySecret)
                    .securityToken(this.securityToken)
                    .region(this.region)
                    .endpointOverride(this.endpointOverride)
                    .pathStyleAccess(this.pathStyleAccess)
                    .bucket(this.bucket)
                    .key(Property.ofValue(object.getKey()))
                    .build()
                    .run(runContext);
                downloaded.add(new Downloaded(object, result.getUri()));
            } catch (Exception e) {
                logger.error("Unable to download oss://{}/{}, it is left in place: {}", rBucket, object.getKey(), e.getMessage());
                failures.add(e);
            }
        }

        if (downloaded.isEmpty()) {
            throw failures.getFirst();
        }

        var triggered = new ArrayList<TriggeredObject>();
        for (var item : downloaded) {
            var key = item.object().getKey();
            var actionFailed = false;
            try {
                if (rAction == Action.DELETE) {
                    Delete.builder()
                        .id(this.getId())
                        .type(Delete.class.getName())
                        .accessKeyId(this.accessKeyId)
                        .accessKeySecret(this.accessKeySecret)
                        .securityToken(this.securityToken)
                        .region(this.region)
                        .endpointOverride(this.endpointOverride)
                        .pathStyleAccess(this.pathStyleAccess)
                        .bucket(this.bucket)
                        .key(Property.ofValue(key))
                        .build()
                        .run(runContext);
                } else {
                    Copy.builder()
                        .id(this.getId())
                        .type(Copy.class.getName())
                        .accessKeyId(this.accessKeyId)
                        .accessKeySecret(this.accessKeySecret)
                        .securityToken(this.securityToken)
                        .region(this.region)
                        .endpointOverride(this.endpointOverride)
                        .pathStyleAccess(this.pathStyleAccess)
                        .bucket(this.bucket)
                        .key(Property.ofValue(key))
                        .destinationBucket(Property.ofValue(rMoveToBucket))
                        .destinationKey(Property.ofValue(rMoveToPrefix + key))
                        .delete(Property.ofValue(true))
                        .build()
                        .run(runContext);
                }
            } catch (Exception e) {
                actionFailed = true;
                logger.error("Unable to {} oss://{}/{}, it will be picked up again by the next poll: {}", rAction, rBucket, key, e.getMessage());
            }

            triggered.add(TriggeredObject.builder()
                .key(key)
                .size(item.object().getSize())
                .etag(item.object().getEtag())
                .lastModified(item.object().getLastModified())
                .uri(item.uri())
                .actionFailed(actionFailed)
                .build());
        }

        logger.info("Found {} objects in oss://{}", triggered.size(), rBucket);

        var output = Output.builder()
            .objects(triggered)
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    private record Downloaded(OssObject object, URI uri) {
    }

    public enum Action {
        DELETE,
        MOVE
    }

    @Builder
    @Getter
    public static class TriggeredObject {
        @Schema(title = "Object key")
        private final String key;

        @Schema(title = "Size in bytes")
        private final Long size;

        @Schema(title = "ETag")
        private final String etag;

        @Schema(title = "Last modified time")
        private final Instant lastModified;

        @Schema(title = "URI of the downloaded object in Kestra internal storage")
        private final URI uri;

        @Schema(
            title = "Whether the delete or move failed",
            description = "When true, the object is still in the bucket and is reported again by the next poll."
        )
        private final Boolean actionFailed;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The objects that triggered the execution")
        private final java.util.List<TriggeredObject> objects;
    }
}
