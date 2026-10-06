package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.ClientException;
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

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Upload a file to an OSS bucket",
    description = "Uploads a file from Kestra internal storage to the given bucket and key. The file is sent in a single request, so it is limited to 5 GB, the maximum size of a simple OSS upload."
)
@Plugin(
    examples = {
        @Example(
            title = "Upload a file to a bucket",
            full = true,
            code = """
                id: oss_upload
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: upload
                    type: io.kestra.plugin.alibaba.oss.Upload
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    bucket: my-bucket
                    key: "landing/{{ inputs.file | fileName }}"
                    from: "{{ inputs.file }}"
                """
        )
    }
)
public class Upload extends AbstractOss implements RunnableTask<Upload.Output> {
    @Schema(
        title = "Source file",
        description = "The `kestra://` URI of the file to upload."
    )
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(
        title = "Object key",
        description = "The key (path) of the object in the bucket."
    )
    @NotNull
    @PluginProperty(group = "destination")
    private Property<String> key;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = rBucket(runContext);
        var rKey = required(runContext, this.key, "key");
        var rFrom = required(runContext, this.from, "from");

        URI source;
        try {
            source = URI.create(rFrom);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("`from` is not a valid URI: " + rFrom, e);
        }

        var tempFile = runContext.workingDir().createTempFile();
        try (InputStream inputStream = runContext.storage().getFile(source)) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        try (var client = client(runContext)) {
            var file = tempFile.toFile();
            try {
                var result = client.getOss().putObject(rBucket, rKey, file);
                runContext.logger().debug("Uploaded {} bytes to oss://{}/{}", file.length(), rBucket, rKey);

                return Output.builder()
                    .etag(result.getETag())
                    .key(rKey)
                    .build();
            } catch (ClientException e) {
                throw translate(e, "upload to", "oss://" + rBucket + "/" + rKey, "write");
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "ETag of the uploaded object")
        private final String etag;

        @Schema(title = "Key of the uploaded object")
        private final String key;
    }
}
