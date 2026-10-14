package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CopyObjectResult;
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

import java.io.IOException;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Copy an object within OSS",
    description = "Copies an object to another key, optionally in another bucket of the same region and account, and can delete the source afterwards to move it. The copy is a single request, so it is limited to objects of at most 1 GB, which is checked before copying. If the source cannot be deleted after a successful copy, the task fails with a message saying the copy exists."
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
    private static final long MAX_COPY_SIZE = 1024L * 1024L * 1024L;

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
            var oss = client.getOss();

            CopyObjectResult result;
            try {
                var size = oss.getObjectMetadata(rBucket, rKey).getContentLength();
                if (size > MAX_COPY_SIZE) {
                    throw new IllegalArgumentException("Unable to copy " + source + ": it is " + size + " bytes and a single-request copy is limited to 1 GB (" + MAX_COPY_SIZE + " bytes)");
                }

                result = oss.copyObject(rBucket, rKey, rDestinationBucket, rDestinationKey);
                runContext.logger().debug("Copied {} to {}", source, destination);
            } catch (OSSException | ClientException e) {
                throw translate(e, "copy", source + " to " + destination, "read and write");
            }

            if (rDelete) {
                try {
                    oss.deleteObject(rBucket, rKey);
                    runContext.logger().debug("Deleted {}", source);
                } catch (OSSException | ClientException e) {
                    throw new IOException("The object was copied to " + destination + " but the source " + source + " could not be deleted and was kept: " + translate(e, "delete", source, "write").getMessage(), e);
                }
            }

            return Output.builder()
                .bucket(rDestinationBucket)
                .key(rDestinationKey)
                .etag(result.getETag())
                .build();
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
