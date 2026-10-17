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
    title = "Create an OSS bucket",
    description = "Creates a bucket in the region of the connection with the default settings (private, Standard storage). Bucket names are unique across all of Alibaba Cloud, so the task fails if the name is taken by someone else."
)
@Plugin(
    examples = {
        @Example(
            title = "Create a bucket",
            full = true,
            code = """
                id: oss_create_bucket
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.alibaba.oss.CreateBucket
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    bucket: my-new-bucket
                """
        )
    }
)
public class CreateBucket extends AbstractOss implements RunnableTask<CreateBucket.Output> {
    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = rBucket(runContext);

        try (var client = client(runContext)) {
            try {
                client.getOss().createBucket(rBucket);
                runContext.logger().debug("Created bucket oss://{}", rBucket);
            } catch (OSSException | ClientException e) {
                throw translateBucket(e, "create bucket", rBucket, "write");
            }
        }

        return Output.builder()
            .bucket(rBucket)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Name of the created bucket")
        private final String bucket;
    }
}
