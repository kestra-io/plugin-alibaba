package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Copy an object within OSS",
    description = "Copies an object to another key, optionally in another bucket of the same region and account, and can delete the source afterwards to move it. The copy is a single request, so it is limited to objects smaller than 1 GB."
)
@Plugin(
    examples = {
        @Example(
            title = "Copy an object, then remove the source to move it",
            full = true,
            code = """
                id: oss_copy
                namespace: company.team

                tasks:
                  - id: move
                    type: io.kestra.plugin.alibaba.oss.Copy
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    bucket: my-bucket
                    key: landing/data.csv
                    destinationKey: archive/data.csv
                    delete: true
                """
        )
    }
)
public class Copy extends AbstractOss implements RunnableTask<Copy.Output> {
    @Schema(
        title = "Source object key",
        description = "The key of the object to copy, in `bucket`."
    )
    @NotNull
    @PluginProperty(group = "source")
    private Property<String> key;

    @Schema(
        title = "Destination bucket",
        description = "The bucket to copy to. Defaults to `bucket`."
    )
    @PluginProperty(group = "destination")
    private Property<String> destinationBucket;

    @Schema(
        title = "Destination object key",
        description = "The key of the copy. It must differ from the source when the bucket is the same."
    )
    @NotNull
    @PluginProperty(group = "destination")
    private Property<String> destinationKey;

    @Schema(
        title = "Delete the source",
        description = "Delete the source object once it is copied, which moves it."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Boolean> delete = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = rBucket(runContext);
        var rKey = required(runContext, this.key, "key");
        var rDestinationBucket = runContext.render(this.destinationBucket).as(String.class).orElse(rBucket);
        var rDestinationKey = required(runContext, this.destinationKey, "destinationKey");
        var rDelete = runContext.render(this.delete).as(Boolean.class).orElse(false);

        if (rBucket.equals(rDestinationBucket) && rKey.equals(rDestinationKey)) {
            throw new IllegalArgumentException("The destination must differ from the source, got: oss://" + rBucket + "/" + rKey);
        }

        var source = "oss://" + rBucket + "/" + rKey;
        var destination = "oss://" + rDestinationBucket + "/" + rDestinationKey;

        try (var client = client(runContext)) {
            try {
                var result = client.getOss().copyObject(rBucket, rKey, rDestinationBucket, rDestinationKey);
                runContext.logger().debug("Copied {} to {}", source, destination);

                if (rDelete) {
                    client.getOss().deleteObject(rBucket, rKey);
                    runContext.logger().debug("Deleted {}", source);
                }

                return Output.builder()
                    .bucket(rDestinationBucket)
                    .key(rDestinationKey)
                    .etag(result.getETag())
                    .build();
            } catch (OSSException | ClientException e) {
                throw translate(e, "copy", source + " to " + destination, "read and write");
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Bucket of the copy")
        private final String bucket;

        @Schema(title = "Key of the copy")
        private final String key;

        @Schema(title = "ETag of the copy")
        private final String etag;
    }
}
