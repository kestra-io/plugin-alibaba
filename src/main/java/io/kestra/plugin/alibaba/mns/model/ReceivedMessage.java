package io.kestra.plugin.alibaba.mns.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ReceivedMessage implements io.kestra.core.models.tasks.Output {
    @Schema(title = "MNS message ID")
    private final String messageId;

    @Schema(
        title = "The message body",
        description = "Text with `serdeType: STRING`, or the parsed value with `serdeType: JSON` (kept as text when it is not valid JSON)."
    )
    private final Object data;
}
