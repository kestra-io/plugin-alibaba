package io.kestra.plugin.alibaba.mns;

import com.aliyun.mns.client.CloudAccount;
import com.aliyun.mns.client.CloudQueue;
import com.aliyun.mns.client.MNSClient;
import com.aliyun.mns.common.http.ClientConfiguration;
import com.aliyuncs.auth.BasicCredentials;
import com.aliyuncs.auth.BasicSessionCredentials;
import com.aliyuncs.auth.StaticCredentialsProvider;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.alibaba.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
public abstract class AbstractMns extends AbstractConnection {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // above the 30 second maximum long-poll wait of a receive request
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(45);
    private static final int MAX_ERROR_RETRY = 3;

    @Schema(
        title = "Account ID",
        description = "The Alibaba Cloud account ID, used to derive the default endpoint `https://<accountId>.mns.<region>.aliyuncs.com`. Not needed when `endpointOverride` is set."
    )
    @PluginProperty(group = "connection")
    protected Property<String> accountId;

    @Schema(
        title = "Queue",
        description = "The name of the MNS queue."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> queue;

    protected String rQueue(RunContext runContext) throws IllegalVariableEvaluationException {
        return required(runContext, this.queue, "queue");
    }

    protected Client client(RunContext runContext) throws IllegalVariableEvaluationException {
        // the SDK signs with the region, so unlike the other services it stays required with `endpointOverride`
        renderedRegion(runContext);
        var rRegion = rRegion(runContext, this);

        var rEndpointOverride = rEndpointOverride(runContext, this);
        var endpoint = rEndpointOverride == null
            ? "https://" + rAccountId(runContext, this.accountId, "accountId") + ".mns." + rRegion + ".aliyuncs.com"
            : rEndpointOverride.getScheme().toLowerCase(Locale.ROOT) + rEndpointOverride.toString().substring(rEndpointOverride.getScheme().length());

        var rAccessKeyId = renderedAccessKeyId(runContext);
        var rAccessKeySecret = renderedAccessKeySecret(runContext);
        var rSecurityToken = renderedSecurityToken(runContext);
        var credentials = rSecurityToken == null
            ? new BasicCredentials(rAccessKeyId, rAccessKeySecret)
            : new BasicSessionCredentials(rAccessKeyId, rAccessKeySecret, rSecurityToken);

        var configuration = new ClientConfiguration();
        configuration.setConnectionTimeout((int) CONNECT_TIMEOUT.toMillis());
        configuration.setSocketTimeout((int) SOCKET_TIMEOUT.toMillis());
        configuration.setMaxErrorRetry(MAX_ERROR_RETRY);

        // credentials go through a provider: the SDK logs the AccessKey arguments of CloudAccount in clear text at DEBUG level
        var account = new CloudAccount(null, null, endpoint, null, new StaticCredentialsProvider(credentials), configuration, rRegion);
        return new Client(account.getMNSClient());
    }

    static String hint(String errorCode, String permission) {
        if (errorCode == null) {
            return "check `region`, `accountId` or `endpointOverride` and network access to MNS";
        }
        return switch (errorCode) {
            case "QueueNotExist" -> "check that the queue exists in this region and account";
            case "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch" -> "check the credentials and that they have the " + permission + " permission";
            case "InternalError", "ServiceUnavailable" -> "MNS returned a service error, retry later";
            default -> errorCode.toLowerCase(Locale.ROOT).contains("limit") || errorCode.toLowerCase(Locale.ROOT).contains("throttl")
                ? "requests are throttled, retry later"
                : "look up the error code in the MNS API reference";
        };
    }

    public static final class Client implements AutoCloseable {
        private final MNSClient mns;

        private Client(MNSClient mns) {
            this.mns = mns;
        }

        public CloudQueue queue(String name) {
            return mns.getQueueRef(name);
        }

        @Override
        public void close() {
            mns.close();
        }
    }
}
