package io.kestra.plugin.alibaba.oss;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Download several objects from an OSS bucket",
    description = "Lists the objects of a bucket by prefix and regular expression, downloads them into Kestra internal storage, and can then leave, delete or move each of them. Objects are downloaded first and only then deleted or moved, so nothing is removed from the bucket before it is safely in internal storage. An object that cannot be downloaded is skipped and left in place; the task only fails when none of the matching objects can be downloaded. If the delete or move of an object fails, the task keeps going and flags that object with `actionFailed`."
)
@Plugin(
    examples = {
        @Example(
            title = "Download the CSV files of a prefix and move them to an archive prefix",
            full = true,
            code = """
                id: oss_downloads
                namespace: company.team

                tasks:
                  - id: downloads
                    type: io.kestra.plugin.alibaba.oss.Downloads
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
public class Downloads extends AbstractOss implements RunnableTask<Downloads.Output> {
    @Schema(title = "Prefix", description = "Only objects whose key starts with this prefix are downloaded.")
    @PluginProperty(group = "source")
    private Property<String> prefix;

    @Schema(
        title = "Regular expression",
        description = "Only objects whose full key matches this Java regular expression are downloaded. It is limited to 256 characters and evaluation is time-boxed to guard against catastrophic backtracking."
    )
    @PluginProperty(group = "advanced")
    private Property<String> regexp;

    @Schema(
        title = "Maximum number of files",
        description = "Maximum number of objects downloaded, between 1 and 1000 (default 25). A warning is logged when more objects match."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Integer> maxFiles = Property.ofValue(List.DEFAULT_MAX_FILES);

    @Schema(
        title = "Action",
        description = "What to do with each object once it is downloaded: `NONE` leaves it, `DELETE` removes it, `MOVE` copies it under `moveToPrefix` and removes the original."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Action> action = Property.ofValue(Action.NONE);

    @Schema(
        title = "Move destination bucket",
        description = "The bucket objects are moved to when `action` is `MOVE`. Defaults to `bucket`."
    )
    @PluginProperty(group = "destination")
    private Property<String> moveToBucket;

    @Schema(
        title = "Move destination prefix",
        description = "Prefix prepended to the original key when `action` is `MOVE`. Required for `MOVE`. The task fails when a moved key in the same bucket would still match `prefix` and `regexp`, because such objects would be matched again by the next run."
    )
    @PluginProperty(group = "destination")
    private Property<String> moveToPrefix;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rBucket = rBucket(runContext);
        var rAction = runContext.render(this.action).as(Action.class).orElse(Action.NONE);
        String rMoveToPrefix = null;
        if (rAction == Action.MOVE) {
            rMoveToPrefix = required(runContext, this.moveToPrefix, "moveToPrefix");
        }
        var rMoveToBucket = runContext.render(this.moveToBucket).as(String.class).orElse(rBucket);
        var rPrefix = runContext.render(this.prefix).as(String.class).orElse(null);
        var rRegexp = runContext.render(this.regexp).as(String.class).map(OssRegexp::compile).orElse(null);
        var rMaxFiles = List.checkMaxFiles(runContext.render(this.maxFiles).as(Integer.class).orElse(List.DEFAULT_MAX_FILES));

        try (var client = client(runContext)) {
            var oss = client.getOss();

            var listing = List.list(oss, rBucket, rPrefix, null, rRegexp, rMaxFiles);
            listing.warnIfLimited(runContext, rBucket, rMaxFiles);

            if (listing.objects().isEmpty()) {
                return Output.builder()
                    .objects(new ArrayList<>())
                    .build();
            }

            if (rAction == Action.MOVE && rMoveToBucket.equals(rBucket)) {
                for (var object : listing.objects()) {
                    var destination = rMoveToPrefix + object.getKey();
                    if ((rPrefix == null || destination.startsWith(rPrefix)) && (rRegexp == null || OssRegexp.matches(rRegexp, destination))) {
                        throw new IllegalArgumentException("`moveToPrefix` must lead outside the downloaded objects, otherwise moved objects are matched again: oss://" + rBucket + "/" + destination + " still matches `prefix` and `regexp`");
                    }
                }
            }

            var downloaded = new ArrayList<Downloaded>();
            var failures = new ArrayList<Exception>();
            for (var object : listing.objects()) {
                try {
                    var fetched = Download.fetch(oss, runContext, rBucket, object.getKey());
                    downloaded.add(new Downloaded(object, fetched.uri()));
                } catch (Exception e) {
                    logger.error("Unable to download oss://{}/{}, it is left in place: {}", rBucket, object.getKey(), e.getMessage());
                    failures.add(e);
                }
            }

            if (downloaded.isEmpty()) {
                throw failures.getFirst();
            }

            var objects = new ArrayList<DownloadedObject>();
            for (var item : downloaded) {
                var key = item.object().getKey();
                var actionFailed = false;
                try {
                    if (rAction == Action.DELETE) {
                        Delete.remove(oss, runContext, rBucket, key);
                    } else if (rAction == Action.MOVE) {
                        Copy.copy(oss, runContext, rBucket, key, rMoveToBucket, rMoveToPrefix + key, true);
                    }
                } catch (Exception e) {
                    actionFailed = true;
                    logger.error("Unable to {} oss://{}/{}, it is still in the bucket: {}", rAction, rBucket, key, e.getMessage());
                }

                objects.add(DownloadedObject.builder()
                    .key(key)
                    .size(item.object().getSize())
                    .etag(item.object().getEtag())
                    .lastModified(item.object().getLastModified())
                    .uri(item.uri())
                    .actionFailed(actionFailed)
                    .build());
            }

            logger.info("Downloaded {} objects from oss://{}", objects.size(), rBucket);

            return Output.builder()
                .objects(objects)
                .build();
        }
    }

    private record Downloaded(OssObject object, URI uri) {
    }

    public enum Action {
        NONE,
        DELETE,
        MOVE
    }

    @Builder
    @Getter
    public static class DownloadedObject {
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
            description = "When true, the object is still in the bucket."
        )
        private final Boolean actionFailed;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The downloaded objects")
        private final java.util.List<DownloadedObject> objects;
    }
}
