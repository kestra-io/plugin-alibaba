package io.kestra.plugin.alibaba;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
public abstract class AbstractConnection extends Task implements AlibabaConnectionInterface {
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
}
