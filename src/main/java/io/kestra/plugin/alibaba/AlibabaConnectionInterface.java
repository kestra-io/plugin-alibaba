package io.kestra.plugin.alibaba;

import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;

public interface AlibabaConnectionInterface {
    @Schema(
        title = "AccessKey ID",
        description = "The AccessKey ID used to authenticate against Alibaba Cloud."
    )
    Property<String> getAccessKeyId();

    @Schema(
        title = "AccessKey secret",
        description = "The AccessKey secret paired with the AccessKey ID."
    )
    Property<String> getAccessKeySecret();

    @Schema(
        title = "Security token",
        description = "Optional Security Token Service (STS) token, required when using temporary credentials."
    )
    Property<String> getSecurityToken();

    @Schema(
        title = "Region",
        description = "The region ID, for example `cn-hangzhou`. It is used to derive the default endpoint `https://oss-<region>.aliyuncs.com`."
    )
    Property<String> getRegion();

    @Schema(
        title = "Endpoint override",
        description = "Custom endpoint URL. When set, it takes precedence over the endpoint derived from the region."
    )
    Property<String> getEndpointOverride();
}
