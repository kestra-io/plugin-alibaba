package io.kestra.plugin.alibaba.mns.model;

import io.kestra.core.serializers.JacksonMapper;

import java.io.IOException;

public enum SerdeType {
    STRING,
    JSON;

    public Object deserialize(String message) throws IOException {
        if (this == JSON) {
            return JacksonMapper.ofJson(false).readValue(message, Object.class);
        }
        return message;
    }
}
