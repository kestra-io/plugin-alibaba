package io.kestra.plugin.alibaba.oss;

import com.aliyun.oss.model.OSSObjectSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Builder
@Getter
public class OssObject {
    @Schema(title = "Object key")
    private final String key;

    @Schema(title = "Size in bytes")
    private final Long size;

    @Schema(title = "ETag")
    private final String etag;

    @Schema(title = "Last modified time")
    private final Instant lastModified;

    @Schema(title = "Storage class")
    private final String storageClass;

    static OssObject of(OSSObjectSummary summary) {
        return OssObject.builder()
            .key(summary.getKey())
            .size(summary.getSize())
            .etag(summary.getETag())
            .lastModified(summary.getLastModified() == null ? null : summary.getLastModified().toInstant())
            .storageClass(summary.getStorageClass())
            .build();
    }
}
