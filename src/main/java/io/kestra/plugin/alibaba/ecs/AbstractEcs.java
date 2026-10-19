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

import java.time.Duration;
import java.util.Locale;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractEcs extends AbstractConnection {
    static final int MAX_INSTANCE_IDS = 100;
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    // ECS sends the region as `RegionId` in every request, so unlike the other services it stays required with `endpointOverride`
    protected String requiredRegion(RunContext runContext) throws IllegalVariableEvaluationException {
        renderedRegion(runContext);
        return rRegion(runContext, this);
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

        var rEndpointOverride = rEndpointOverride(runContext, this);
        if (rEndpointOverride == null) {
            config.setEndpoint("ecs." + rRegion + ".aliyuncs.com");
        } else {
            config.setProtocol(rEndpointOverride.getScheme().toLowerCase(Locale.ROOT)).setEndpoint(rEndpointOverride.getAuthority());
        }

        return new Client(config);
    }
}
