package io.kestra.plugin.alibaba;

import io.kestra.core.models.property.Property;

public interface AlibabaConnectionInterface {
    Property<String> getAccessKeyId();

    Property<String> getAccessKeySecret();

    Property<String> getSecurityToken();

    Property<String> getRegion();

    Property<String> getEndpointOverride();
}
