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

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMns extends AbstractConnection {
    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[0-9]+$");
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
        var rRegion = renderedRegion(runContext);
        if (!REGION_PATTERN.matcher(rRegion).matches()) {
            throw new IllegalArgumentException("`region` must only contain lowercase letters, digits and dashes, got: " + rRegion);
        }

        var rEndpointOverride = renderedEndpoint(runContext);
        String endpoint;
        if (rEndpointOverride == null) {
            var rAccountId = required(runContext, this.accountId, "accountId");
            if (!ACCOUNT_PATTERN.matcher(rAccountId).matches()) {
                throw new IllegalArgumentException("`accountId` must only contain digits, got: " + rAccountId);
            }
            endpoint = "https://" + rAccountId + ".mns." + rRegion + ".aliyuncs.com";
        } else {
            var uri = endpointUri(rEndpointOverride);
            if (uri.getScheme().equals("http")) {
                runContext.logger().warn("`endpointOverride` uses plain http, credentials and signed requests are sent unencrypted to {}", uri.getAuthority());
            }
            endpoint = rEndpointOverride;
        }

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
