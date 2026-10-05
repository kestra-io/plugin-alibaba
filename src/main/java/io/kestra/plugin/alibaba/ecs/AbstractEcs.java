package io.kestra.plugin.alibaba.ecs;

import com.aliyun.ecs20140526.Client;
import com.aliyun.teaopenapi.models.Config;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.alibaba.AbstractConnection;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractEcs extends AbstractConnection {
    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z0-9-]+$");

    protected String rRegion(RunContext runContext) throws IllegalVariableEvaluationException {
        var regionId = required(runContext, this.region, "region");
        if (!REGION_PATTERN.matcher(regionId).matches()) {
            throw new IllegalArgumentException("`region` must only contain lowercase letters, digits and dashes, got: " + regionId);
        }
        return regionId;
    }

    protected static String required(RunContext runContext, Property<String> property, String name) throws IllegalVariableEvaluationException {
        return runContext.render(property).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`" + name + "` is required"));
    }

    protected Client client(RunContext runContext, String regionId) throws Exception {
        var config = new Config()
            .setAccessKeyId(required(runContext, this.accessKeyId, "accessKeyId"))
            .setAccessKeySecret(required(runContext, this.accessKeySecret, "accessKeySecret"))
            .setSecurityToken(runContext.render(this.securityToken).as(String.class).orElse(null))
            .setRegionId(regionId);

        var override = runContext.render(this.endpointOverride).as(String.class).orElse(null);
        if (override == null) {
            config.setEndpoint("ecs." + regionId + ".aliyuncs.com");
        } else {
            var uri = endpointUri(override);
            if (uri.getScheme().equals("http")) {
                runContext.logger().warn("`endpointOverride` uses plain http, credentials and signed requests are sent unencrypted to {}", uri.getAuthority());
            }
            config.setProtocol(uri.getScheme()).setEndpoint(uri.getAuthority());
        }

        return new Client(config);
    }

    private static URI endpointUri(String endpoint) {
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
        return uri;
    }
}
