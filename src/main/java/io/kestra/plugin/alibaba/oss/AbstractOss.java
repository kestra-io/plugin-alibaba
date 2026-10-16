package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.alibaba.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractOss extends AbstractConnection {
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    @Schema(
        title = "Bucket",
        description = "The name of the OSS bucket."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> bucket;

    @Schema(
        title = "Path-style access",
        description = "Address buckets as `<endpoint>/<bucket>` instead of `<bucket>.<endpoint>`. Useful with custom endpoints such as a local emulator."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    protected Property<Boolean> pathStyleAccess = Property.ofValue(false);

    protected String rBucket(RunContext runContext) throws IllegalVariableEvaluationException {
        return required(runContext, this.bucket, "bucket");
    }

    protected Client client(RunContext runContext) throws IllegalVariableEvaluationException {
        var rPathStyleAccess = runContext.render(this.pathStyleAccess).as(Boolean.class).orElse(false);
        var rEndpointOverride = rEndpointOverride(runContext, this);
        if (rEndpointOverride != null && rEndpointOverride.getScheme().equalsIgnoreCase("http") && !rPathStyleAccess) {
            throw new IllegalArgumentException("`endpointOverride` must use https unless `pathStyleAccess` is true, got: " + rEndpointOverride);
        }
        var endpoint = rEndpointOverride != null ? rEndpointOverride.toString() : "https://oss-" + rRegion(runContext, this) + ".aliyuncs.com";

        var rAccessKeyId = required(runContext, this.accessKeyId, "accessKeyId");
        var rAccessKeySecret = required(runContext, this.accessKeySecret, "accessKeySecret");
        var rSecurityToken = runContext.render(this.securityToken).as(String.class).orElse(null);

        var configuration = new ClientBuilderConfiguration();
        configuration.setSLDEnabled(rPathStyleAccess);

        return new Client(new OSSClientBuilder().build(endpoint, rAccessKeyId, rAccessKeySecret, rSecurityToken, configuration));
    }

    protected static IOException translate(RuntimeException e, String operation, String target, String access) {
        if (e instanceof OSSException ossException) {
            return new IOException(
                "Unable to " + operation + " " + target + " (" + ossException.getErrorCode() + "): check that the bucket and key exist and that the credentials have " + access + " access",
                e
            );
        }

        return new IOException(
            "Unable to " + operation + " " + target + ": check `region` or `endpointOverride` and network access to the OSS endpoint (" + redactUrls(e.getMessage()) + ")",
            e
        );
    }

    private static String redactUrls(String message) {
        return message == null ? "no details" : URL_PATTERN.matcher(message).replaceAll("<url>");
    }

    public static final class Client implements AutoCloseable {
        private final OSS oss;

        private Client(OSS oss) {
            this.oss = oss;
        }

        public OSS getOss() {
            return oss;
        }

        @Override
        public void close() {
            oss.shutdown();
        }
    }
}
