package io.kestra.plugin.alibaba.mns;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.plugin.alibaba.AlibabaConnectionInterface;
import io.kestra.plugin.alibaba.mns.model.SerdeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
public abstract class AbstractMnsTrigger extends AbstractTrigger implements AlibabaConnectionInterface {
    @Schema(title = "AccessKey ID", description = "The AccessKey ID used to authenticate against Alibaba Cloud.")
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> accessKeyId;

    @Schema(title = "AccessKey secret", description = "The AccessKey secret paired with the AccessKey ID.")
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> accessKeySecret;

    @Schema(title = "Security token", description = "Optional Security Token Service (STS) token, required when using temporary credentials.")
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> securityToken;

    @Schema(title = "Region", description = "The Alibaba Cloud region ID, for example `cn-hangzhou`. The service endpoint is derived from it unless `endpointOverride` is set.")
    @PluginProperty(group = "connection")
    protected Property<String> region;

    @Schema(title = "Endpoint override", description = "Custom http or https endpoint URL. When set, it takes precedence over the endpoint derived from `region`.")
    @PluginProperty(group = "connection")
    protected Property<String> endpointOverride;

    @Schema(
        title = "Account ID",
        description = "The Alibaba Cloud account ID, used to derive the default endpoint `https://<accountId>.mns.<region>.aliyuncs.com`. Not needed when `endpointOverride` is set."
    )
    @PluginProperty(group = "connection")
    protected Property<String> accountId;

    @Schema(title = "Queue", description = "The name of the MNS queue.")
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> queue;

    @Schema(
        title = "Serde type",
        description = "`STRING` keeps message bodies as text, `JSON` parses them. A body that is not valid JSON is kept as a string and logged."
    )
    @NotNull
    @PluginProperty(group = "processing")
    @Builder.Default
    protected Property<SerdeType> serdeType = Property.ofValue(SerdeType.STRING);

    @Schema(
        title = "Auto-delete",
        description = "Delete messages from the queue once they are handed to Kestra: `Trigger` deletes them once the ION file is stored, `RealtimeTrigger` once Kestra has taken them and requests more messages. When false, they become visible again after the queue's visibility timeout."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    protected Property<Boolean> autoDelete = Property.ofValue(true);

    protected Consume.ConsumeBuilder<?, ?> consumeTask() {
        return Consume.builder()
            .id(this.id)
            .type(Consume.class.getName())
            .accessKeyId(this.accessKeyId)
            .accessKeySecret(this.accessKeySecret)
            .securityToken(this.securityToken)
            .region(this.region)
            .endpointOverride(this.endpointOverride)
            .accountId(this.accountId)
            .queue(this.queue)
            .serdeType(this.serdeType)
            .autoDelete(this.autoDelete);
    }
}
