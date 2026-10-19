package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ListObjectsV2Request;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List objects in an OSS bucket",
    description = "Lists the objects of a bucket, optionally filtered by prefix and regular expression. Folder placeholder objects (keys ending with `/`) are skipped. Results are capped by `maxFiles`."
)
@Plugin(
    examples = {
        @Example(
            title = "List the CSV files under a prefix",
            full = true,
            code = """
                id: oss_list
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.alibaba.oss.List
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    bucket: my-bucket
                    prefix: landing/
                    regexp: ".*\\\\.csv"
                """
        )
    }
)
public class List extends AbstractOss implements RunnableTask<List.Output> {
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_FILES_LIMIT = 1000;
    static final int DEFAULT_MAX_FILES = 25;

    @Schema(
        title = "Prefix",
        description = "Only objects whose key starts with this prefix are listed."
    )
    @PluginProperty(group = "source")
    private Property<String> prefix;

    @Schema(
        title = "Delimiter",
        description = "Groups keys that share the prefix up to the next delimiter, for example `/` to list a single folder level. Grouped keys are not returned."
    )
    @PluginProperty(group = "advanced")
    private Property<String> delimiter;

    @Schema(
        title = "Regular expression",
        description = "Only objects whose full key matches this Java regular expression are returned. It is limited to 256 characters and evaluation is time-boxed to guard against catastrophic backtracking."
    )
    @PluginProperty(group = "advanced")
    private Property<String> regexp;

    @Schema(
        title = "Maximum number of files",
        description = "Maximum number of objects returned, between 1 and 1000 (default 25). A warning is logged when more objects match."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Integer> maxFiles = Property.ofValue(25);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = rBucket(runContext);
        var rPrefix = runContext.render(this.prefix).as(String.class).orElse(null);
        var rDelimiter = runContext.render(this.delimiter).as(String.class).orElse(null);
        var rRegexp = runContext.render(this.regexp).as(String.class).map(OssRegexp::compile).orElse(null);
        var rMaxFiles = checkMaxFiles(runContext.render(this.maxFiles).as(Integer.class).orElse(DEFAULT_MAX_FILES));

        Listing listing;
        try (var client = client(runContext)) {
            listing = list(client.getOss(), rBucket, rPrefix, rDelimiter, rRegexp, rMaxFiles);
        }

        listing.warnIfLimited(runContext, rBucket, rMaxFiles);
        runContext.logger().debug("Listed {} objects in oss://{}", listing.objects().size(), rBucket);

        return Output.builder()
            .objects(listing.objects())
            .build();
    }

    static int checkMaxFiles(int maxFiles) {
        if (maxFiles < 1 || maxFiles > MAX_FILES_LIMIT) {
            throw new IllegalArgumentException("`maxFiles` must be between 1 and " + MAX_FILES_LIMIT + ", got: " + maxFiles);
        }
        return maxFiles;
    }

    static Listing list(OSS oss, String bucket, String prefix, String delimiter, Pattern regexp, int maxFiles) throws IOException {
        var objects = new ArrayList<OssObject>();
        var limited = false;

        try {
            var request = new ListObjectsV2Request(bucket);
            request.setPrefix(prefix);
            request.setDelimiter(delimiter);
            request.setMaxKeys(PAGE_SIZE);

            boolean truncated;
            do {
                var result = oss.listObjectsV2(request);
                for (var summary : result.getObjectSummaries()) {
                    var key = summary.getKey();
                    if (key.endsWith("/") || (regexp != null && !OssRegexp.matches(regexp, key))) {
                        continue;
                    }
                    if (objects.size() >= maxFiles) {
                        limited = true;
                        break;
                    }
                    objects.add(OssObject.of(summary));
                }

                truncated = result.isTruncated();
                request.setContinuationToken(result.getNextContinuationToken());
            } while (truncated && !limited);
        } catch (OSSException | ClientException e) {
            throw translate(e, "list", "oss://" + bucket + "/" + (prefix == null ? "" : prefix), "list");
        }

        return new Listing(objects, limited);
    }

    record Listing(java.util.List<OssObject> objects, boolean limited) {
        void warnIfLimited(RunContext runContext, String bucket, int maxFiles) {
            if (limited) {
                runContext.logger().warn("More than {} objects match in oss://{}, only the first {} are returned", maxFiles, bucket, maxFiles);
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The matching objects")
        private final java.util.List<OssObject> objects;
    }
}
