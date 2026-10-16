package io.kestra.plugin.alibaba.ecs;

import com.aliyun.ecs20140526.Client;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.alibaba.AbstractConnection;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractEcs extends AbstractConnection {
    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    static final int MAX_INSTANCE_IDS = 100;
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    protected String rRegion(RunContext runContext) throws IllegalVariableEvaluationException {
        var rRegion = renderedRegion(runContext);
        if (!REGION_PATTERN.matcher(rRegion).matches()) {
            throw new IllegalArgumentException("`region` must only contain lowercase letters, digits and dashes, got: " + rRegion);
        }
        return rRegion;
    }

    protected static void checkInstanceIdCount(java.util.List<String> rInstanceIds) {
        if (rInstanceIds.size() > MAX_INSTANCE_IDS) {
            throw new IllegalArgumentException("`instanceIds` accepts at most " + MAX_INSTANCE_IDS + " IDs, got " + rInstanceIds.size());
        }
    }

    protected RuntimeOptions runtimeOptions(RunContext runContext) throws IllegalVariableEvaluationException {
        // 90% of the task `timeout`, so the SDK fails first with the actionable message instead of Kestra's generic timeout
        var rReadTimeout = runContext.render(this.getTimeout()).as(Duration.class)
            .map(timeout -> timeout.multipliedBy(9).dividedBy(10))
            .orElse(DEFAULT_READ_TIMEOUT);
        return new RuntimeOptions()
            .setConnectTimeout((int) CONNECT_TIMEOUT.toMillis())
            .setReadTimeout((int) Math.min(rReadTimeout.toMillis(), Integer.MAX_VALUE));
    }

    protected Client client(RunContext runContext, String rRegion) throws Exception {
        var config = new Config()
            .setAccessKeyId(renderedAccessKeyId(runContext))
            .setAccessKeySecret(renderedAccessKeySecret(runContext))
            .setSecurityToken(renderedSecurityToken(runContext))
            .setRegionId(rRegion);

        var rEndpointOverride = renderedEndpoint(runContext);
        if (rEndpointOverride == null) {
            config.setEndpoint("ecs." + rRegion + ".aliyuncs.com");
        } else {
            var uri = endpointUri(rEndpointOverride);
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
