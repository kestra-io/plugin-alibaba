package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientException;
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
import java.net.URI;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractOss extends AbstractConnection {
    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z0-9-]+$");

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

    protected static String required(RunContext runContext, Property<String> property, String name) throws IllegalVariableEvaluationException {
        return runContext.render(property).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`" + name + "` is required"));
    }

    protected Client client(RunContext runContext) throws IllegalVariableEvaluationException {
        var endpoint = runContext.render(this.endpointOverride).as(String.class).orElse(null);
        if (endpoint == null) {
            var regionId = runContext.render(this.region).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("Either `region` or `endpointOverride` must be set"));
            if (!REGION_PATTERN.matcher(regionId).matches()) {
                throw new IllegalArgumentException("`region` must only contain lowercase letters, digits and dashes, got: " + regionId);
            }
            endpoint = "https://oss-" + regionId + ".aliyuncs.com";
        }

        var pathStyle = runContext.render(this.pathStyleAccess).as(Boolean.class).orElse(false);
        if (runContext.render(this.endpointOverride).as(String.class).isPresent()) {
            validateEndpoint(endpoint, pathStyle);
            if (endpoint.startsWith("http://")) {
                runContext.logger().warn("`endpointOverride` uses plain http, credentials and data are sent unencrypted");
            }
        }

        var id = required(runContext, this.accessKeyId, "accessKeyId");
        var secret = required(runContext, this.accessKeySecret, "accessKeySecret");
        var token = runContext.render(this.securityToken).as(String.class).orElse(null);

        var configuration = new ClientBuilderConfiguration();
        configuration.setSLDEnabled(pathStyle);

        return new Client(new OSSClientBuilder().build(endpoint, id, secret, token, configuration));
    }

    protected static IOException translate(ClientException e, String operation, String target, String access) {
        if (e instanceof OSSException ossException) {
            return new IOException(
                "Unable to " + operation + " " + target + " (" + ossException.getErrorCode() + "): check that the bucket and key exist and that the credentials have " + access + " access",
                e
            );
        }

        return new IOException(
            "Unable to " + operation + " " + target + ": check `region` or `endpointOverride` and network access to the OSS endpoint (" + e.getMessage() + ")",
            e
        );
    }

    private static void validateEndpoint(String endpoint, boolean pathStyle) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("`endpointOverride` is not a valid URL: " + endpoint, e);
        }

        var scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("`endpointOverride` must be an http or https URL, got: " + endpoint);
        }

        if (scheme.equals("http") && !pathStyle) {
            throw new IllegalArgumentException("`endpointOverride` must use https unless `pathStyleAccess` is true, got: " + endpoint);
        }
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
