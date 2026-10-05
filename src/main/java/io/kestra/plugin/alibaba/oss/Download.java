package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.OSSObject;
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
    title = "Download an object from an OSS bucket",
    description = "Downloads an object into Kestra internal storage and returns its URI."
)
@Plugin(
    examples = {
        @Example(
            title = "Download an object to internal storage",
            full = true,
            code = """
                id: oss_download
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.alibaba.oss.Download
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    bucket: my-bucket
                    key: landing/data.csv
                """
        )
    }
)
public class Download extends AbstractOss implements RunnableTask<Download.Output> {
    @Schema(
        title = "Object key",
        description = "The key (path) of the object to download."
    )
    @NotNull
    @PluginProperty(group = "source")
    private Property<String> key;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = rBucket(runContext);
        var rKey = required(runContext, this.key, "key");

        try (var client = client(runContext)) {
            OSSObject object;
            try {
                object = client.getOss().getObject(rBucket, rKey);
            } catch (OSSException e) {
                throw new IOException("Unable to download oss://" + rBucket + "/" + rKey + " (" + e.getErrorCode() + "): check that the key and bucket exist and that the credentials have read access", e);
            }

            var tempFile = runContext.workingDir().createTempFile();
            try (InputStream inputStream = object.getObjectContent()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            var size = Files.size(tempFile);
            var uri = runContext.storage().putFile(tempFile.toFile());
            runContext.logger().debug("Downloaded oss://{}/{} ({} bytes)", rBucket, rKey, size);

            return Output.builder()
                .uri(uri)
                .size(size)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI of the downloaded file in Kestra internal storage")
        private final URI uri;

        @Schema(title = "Size of the downloaded file in bytes")
        private final Long size;
    }
}
