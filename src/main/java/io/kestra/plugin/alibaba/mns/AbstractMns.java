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
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMns extends AbstractConnection {
    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[0-9]+$");

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

    protected static String required(RunContext runContext, Property<String> property, String name) throws IllegalVariableEvaluationException {
        return runContext.render(property).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`" + name + "` is required"));
    }

    protected String rQueue(RunContext runContext) throws IllegalVariableEvaluationException {
        return required(runContext, this.queue, "queue");
    }

    protected Client client(RunContext runContext) throws IllegalVariableEvaluationException {
        var regionId = required(runContext, this.region, "region");
        if (!REGION_PATTERN.matcher(regionId).matches()) {
            throw new IllegalArgumentException("`region` must only contain lowercase letters, digits and dashes, got: " + regionId);
        }

        var override = runContext.render(this.endpointOverride).as(String.class).orElse(null);
        String endpoint;
        if (override == null) {
            var account = required(runContext, this.accountId, "accountId");
            if (!ACCOUNT_PATTERN.matcher(account).matches()) {
                throw new IllegalArgumentException("`accountId` must only contain digits, got: " + account);
            }
            endpoint = "https://" + account + ".mns." + regionId + ".aliyuncs.com";
        } else {
            var uri = endpointUri(override);
            if (uri.getScheme().equals("http")) {
                runContext.logger().warn("`endpointOverride` uses plain http, credentials and signed requests are sent unencrypted to {}", uri.getAuthority());
            }
            endpoint = override;
        }

        var id = required(runContext, this.accessKeyId, "accessKeyId");
        var secret = required(runContext, this.accessKeySecret, "accessKeySecret");
        var token = runContext.render(this.securityToken).as(String.class).orElse(null);
        var credentials = token == null ? new BasicCredentials(id, secret) : new BasicSessionCredentials(id, secret, token);

        // credentials go through a provider: the SDK logs the AccessKey arguments of CloudAccount in clear text at DEBUG level
        var account = new CloudAccount(null, null, endpoint, null, new StaticCredentialsProvider(credentials), new ClientConfiguration(), regionId);
        return new Client(account.getMNSClient());
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
