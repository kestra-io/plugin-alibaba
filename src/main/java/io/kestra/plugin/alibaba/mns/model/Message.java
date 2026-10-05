package io.kestra.plugin.alibaba.mns.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
public class Message implements io.kestra.core.models.tasks.Output {
    @Schema(
        title = "The message data",
        description = "A string or a structured value (map, list) serialized to JSON before sending. Strings of inline messages are rendered at runtime, rows read from a `kestra://` file are sent as is."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Object data;

    @Schema(
        title = "Delay in seconds",
        description = "How long the message stays invisible after it is sent, from 0 to 604800 seconds."
    )
    @PluginProperty(group = "advanced")
    private Integer delaySeconds;

    @Schema(
        title = "Priority",
        description = "From 1 (highest) to 16 (lowest), defaults to 8."
    )
    @PluginProperty(group = "advanced")
    private Integer priority;

    public com.aliyun.mns.model.Message toMns(RunContext runContext, boolean render) throws IllegalVariableEvaluationException, JsonProcessingException {
        var message = new com.aliyun.mns.model.Message();
        message.setMessageBodyAsRawString(body(runContext, render));
        if (delaySeconds != null) {
            message.setDelaySeconds(delaySeconds);
        }
        if (priority != null) {
            message.setPriority(priority);
        }
        return message;
    }

    private String body(RunContext runContext, boolean render) throws IllegalVariableEvaluationException, JsonProcessingException {
        if (data instanceof String s) {
            return render ? runContext.render(s) : s;
        }
        return JacksonMapper.ofJson(false).writeValueAsString(data);
    }
}
