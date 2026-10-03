package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.alibaba.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
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
public abstract class AbstractOss extends AbstractConnection {
    @Schema(
        title = "Bucket",
        description = "The name of the OSS bucket."
    )
    @PluginProperty(group = "main")
    protected Property<String> bucket;

    @Schema(
        title = "Path-style access",
        description = "Address buckets as `<endpoint>/<bucket>` instead of `<bucket>.<endpoint>`. Useful with custom endpoints such as a local emulator."
    )
    @PluginProperty(group = "advanced")
    @lombok.Builder.Default
    protected Property<Boolean> pathStyleAccess = Property.ofValue(false);

    /**
     * Builds an OSS client. The returned client must be closed by the caller, use it in a try-with-resources.
     */
    protected OSS client(RunContext runContext) throws IllegalVariableEvaluationException {
        String endpoint = runContext.render(this.endpointOverride).as(String.class).orElse(null);
        if (endpoint == null) {
            String regionId = runContext.render(this.region).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("Either `region` or `endpointOverride` must be set"));
            endpoint = "https://oss-" + regionId + ".aliyuncs.com";
        }

        String id = runContext.render(this.accessKeyId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`accessKeyId` is required"));
        String secret = runContext.render(this.accessKeySecret).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`accessKeySecret` is required"));
        String token = runContext.render(this.securityToken).as(String.class).orElse(null);

        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSLDEnabled(runContext.render(this.pathStyleAccess).as(Boolean.class).orElse(false));

        return new OSSClientBuilder().build(endpoint, id, secret, token, configuration);
    }
}
