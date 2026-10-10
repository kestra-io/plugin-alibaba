package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.ClientException;
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
        description = "Only objects whose full key matches this regular expression are returned."
    )
    @PluginProperty(group = "advanced")
    private Property<String> regexp;

    @Schema(
        title = "Maximum number of files",
        description = "Maximum number of objects returned. A warning is logged when more objects match."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Integer> maxFiles = Property.ofValue(25);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = rBucket(runContext);
        var rPrefix = runContext.render(this.prefix).as(String.class).orElse(null);
        var rDelimiter = runContext.render(this.delimiter).as(String.class).orElse(null);
        var rRegexp = runContext.render(this.regexp).as(String.class).map(Pattern::compile).orElse(null);
        var rMaxFiles = runContext.render(this.maxFiles).as(Integer.class).orElse(25);
        if (rMaxFiles < 1) {
            throw new IllegalArgumentException("`maxFiles` must be at least 1, got: " + rMaxFiles);
        }

        var objects = new ArrayList<OssObject>();
        var limited = false;

        try (var client = client(runContext)) {
            try {
                var request = new ListObjectsV2Request(rBucket);
                request.setPrefix(rPrefix);
                request.setDelimiter(rDelimiter);
                request.setMaxKeys(PAGE_SIZE);

                boolean truncated;
                do {
                    var result = client.getOss().listObjectsV2(request);
                    for (var summary : result.getObjectSummaries()) {
                        var key = summary.getKey();
                        if (key.endsWith("/") || (rRegexp != null && !rRegexp.matcher(key).matches())) {
                            continue;
                        }
                        if (objects.size() >= rMaxFiles) {
                            limited = true;
                            break;
                        }
                        objects.add(OssObject.of(summary));
                    }

                    truncated = result.isTruncated();
                    request.setContinuationToken(result.getNextContinuationToken());
                } while (truncated && !limited);
            } catch (OSSException | ClientException e) {
                throw translate(e, "list", "oss://" + rBucket + "/" + (rPrefix == null ? "" : rPrefix), "list");
            }
        }

        if (limited) {
            runContext.logger().warn("More than {} objects match in oss://{}, only the first {} are returned", rMaxFiles, rBucket, rMaxFiles);
        }
        runContext.logger().debug("Listed {} objects in oss://{}", objects.size(), rBucket);

        return Output.builder()
            .objects(objects)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The matching objects")
        private final java.util.List<OssObject> objects;
    }
}
