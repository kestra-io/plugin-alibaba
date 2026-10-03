package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.PutObjectResult;
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

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Upload a file to an OSS bucket",
    description = "Uploads a file from Kestra internal storage to the given bucket and key."
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
        String bucket = runContext.render(this.bucket).as(String.class).orElseThrow();
        String key = runContext.render(this.key).as(String.class).orElseThrow();
        URI source = URI.create(runContext.render(this.from).as(String.class).orElseThrow());

        // copy to a local file first so the request carries a Content-Length
        Path tempFile = runContext.workingDir().createTempFile();
        try (InputStream inputStream = runContext.storage().getFile(source)) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        try (Client client = client(runContext)) {
            OSS oss = client.getOss();
            File file = tempFile.toFile();
            PutObjectResult result = oss.putObject(bucket, key, file);
            runContext.logger().debug("Uploaded {} bytes to oss://{}/{}", file.length(), bucket, key);

            return Output.builder()
                .etag(result.getETag())
                .key(key)
                .build();
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
