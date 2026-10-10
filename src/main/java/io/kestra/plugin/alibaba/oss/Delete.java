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
    title = "Delete an object from an OSS bucket",
    description = "Deletes a single object. OSS reports success when the object does not exist, so deleting a missing key does not fail."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete an object",
            full = true,
            code = """
                id: oss_delete
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.alibaba.oss.Delete
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    bucket: my-bucket
                    key: landing/data.csv
                """
        )
    }
)
public class Delete extends AbstractOss implements RunnableTask<Delete.Output> {
    @Schema(
        title = "Object key",
        description = "The key (path) of the object to delete."
    )
    @NotNull
    @PluginProperty(group = "source")
    private Property<String> key;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = rBucket(runContext);
        var rKey = required(runContext, this.key, "key");

        try (var client = client(runContext)) {
            try {
                client.getOss().deleteObject(rBucket, rKey);
                runContext.logger().debug("Deleted oss://{}/{}", rBucket, rKey);

                return Output.builder()
                    .key(rKey)
                    .build();
            } catch (OSSException | ClientException e) {
                throw translate(e, "delete", "oss://" + rBucket + "/" + rKey, "write");
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Key of the deleted object")
        private final String key;
    }
}
