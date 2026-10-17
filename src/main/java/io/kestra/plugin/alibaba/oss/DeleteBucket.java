package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "Delete an OSS bucket",
    description = "Deletes a bucket. OSS refuses to delete a bucket that still contains objects or incomplete multipart uploads, so empty it first, for example with `Downloads` using `action: DELETE`."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete a bucket",
            full = true,
            code = """
                id: oss_delete_bucket
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.alibaba.oss.DeleteBucket
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    bucket: my-old-bucket
                """
        )
    }
)
public class DeleteBucket extends AbstractOss implements RunnableTask<DeleteBucket.Output> {
    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = rBucket(runContext);

        try (var client = client(runContext)) {
            try {
                client.getOss().deleteBucket(rBucket);
                runContext.logger().debug("Deleted bucket oss://{}", rBucket);
            } catch (OSSException | ClientException e) {
                throw translateBucket(e, "delete bucket", rBucket, "write");
            }
        }

        return Output.builder()
            .bucket(rBucket)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Name of the deleted bucket")
        private final String bucket;
    }
}
