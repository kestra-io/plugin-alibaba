package io.kestra.plugin.alibaba;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Shared interface for Alibaba Cloud connection properties.
 */
public interface AlibabaConnectionInterface {
    @Schema(
        title = "The AccessKey ID",
        description = "The AccessKey ID used to authenticate with Alibaba Cloud."
    )
    @PluginProperty(group = "connection")
    Property<String> getAccessKeyId();

    @Schema(
        title = "The AccessKey Secret",
        description = "The AccessKey Secret used to authenticate with Alibaba Cloud."
    )
    @PluginProperty(group = "connection", secret = true)
    Property<String> getAccessKeySecret();

    @Schema(
        title = "Security Token",
        description = "The optional STS security token for temporary credentials."
    )
    @PluginProperty(group = "connection", secret = true)
    Property<String> getSecurityToken();

    @Schema(
        title = "Region",
        description = "The Alibaba Cloud region, e.g., 'cn-hangzhou', 'cn-shanghai'."
    )
    @PluginProperty(group = "connection")
    Property<String> getRegion();

    @Schema(
        title = "Endpoint override",
        description = "Custom endpoint URL. If omitted, the default regional endpoint is derived."
    )
    @PluginProperty(group = "connection")
    Property<String> getEndpoint();
}
