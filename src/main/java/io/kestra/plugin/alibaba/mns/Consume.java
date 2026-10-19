package io.kestra.plugin.alibaba.mns;

import com.aliyun.mns.client.CloudQueue;
import com.aliyun.mns.common.BatchDeleteException;
import com.aliyun.mns.common.ClientException;
import com.aliyun.mns.common.ServiceException;
import com.aliyun.mns.common.ServiceHandlingRequiredException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.alibaba.mns.model.SerdeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Consume messages from an MNS queue",
    description = "Receives messages until the queue is empty, `maxRecords` is reached or `maxDuration` (20 seconds by default) elapses, stores them in an ION file in internal storage and then, by default, deletes them from the queue. Delivery is at least once: messages are only deleted after the file is stored."
)
@Plugin(
    examples = {
        @Example(
            title = "Consume up to 100 JSON messages",
            full = true,
            code = """
                id: mns_consume
                namespace: company.team

                tasks:
                  - id: consume
                    type: io.kestra.plugin.alibaba.mns.Consume
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    accountId: "{{ secret('ALIBABA_ACCOUNT_ID') }}"
                    queue: orders
                    serdeType: JSON
                    maxRecords: 100
                    maxDuration: PT20S
                """
        )
    },
    metrics = {
        @Metric(
            name = "mns.consume.messages",
            type = Counter.TYPE,
            unit = "messages",
            description = "Number of messages consumed from the MNS queue."
        )
    }
)
public class Consume extends AbstractMns implements RunnableTask<Consume.Output> {
    static final int BATCH_SIZE = 16;
    static final String MESSAGE_NOT_EXIST = "MessageNotExist";
    static final Duration DEFAULT_MAX_DURATION = Duration.ofSeconds(20);
    static final int DEFAULT_MAX_RECORDS = 1000;

    @Schema(
        title = "Max records",
        description = "Stop after consuming this many messages, at least 1, 1000 by default."
    )
    @NotNull
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<Integer> maxRecords = Property.ofValue(DEFAULT_MAX_RECORDS);

    @Schema(
        title = "Max duration",
        description = "Stop after this duration elapses, 20 seconds by default. Keep it below the queue's visibility timeout (30 seconds by default): messages are deleted at the end of the run, and a message whose receipt handle expired in the meantime is delivered again."
    )
    @NotNull
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<Duration> maxDuration = Property.ofValue(DEFAULT_MAX_DURATION);

    @Schema(
        title = "Serde type",
        description = "`STRING` keeps message bodies as text, `JSON` parses them. A body that is not valid JSON is kept as a string and logged."
    )
    @NotNull
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<SerdeType> serdeType = Property.ofValue(SerdeType.STRING);

    @Schema(
        title = "Auto-delete",
        description = "Delete messages from the queue once the ION file is stored. When false, they become visible again after the queue's visibility timeout."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Boolean> autoDelete = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rQueue = rQueue(runContext);
        var rMaxRecords = runContext.render(this.maxRecords).as(Integer.class).orElse(DEFAULT_MAX_RECORDS);
        if (rMaxRecords < 1) {
            throw new IllegalArgumentException("`maxRecords` must be at least 1, got " + rMaxRecords);
        }
        var rMaxDuration = runContext.render(this.maxDuration).as(Duration.class).orElse(DEFAULT_MAX_DURATION);
        if (rMaxDuration.isNegative() || rMaxDuration.isZero()) {
            throw new IllegalArgumentException("`maxDuration` must be positive, got " + rMaxDuration);
        }
        var rSerdeType = runContext.render(this.serdeType).as(SerdeType.class).orElse(SerdeType.STRING);
        var rAutoDelete = runContext.render(this.autoDelete).as(Boolean.class).orElse(true);

        try (var client = client(runContext)) {
            var queue = client.queue(rQueue);
            var file = runContext.workingDir().createTempFile(".ion").toFile();
            var handles = receiveInto(runContext, file, queue, rQueue, rSerdeType, rMaxRecords, Instant.now().plus(rMaxDuration));

            URI uri = null;
            if (!handles.isEmpty()) {
                uri = runContext.storage().putFile(file);
                if (rAutoDelete) {
                    deleteAll(runContext, queue, rQueue, handles);
                }
            }

            runContext.metric(Counter.of("mns.consume.messages", handles.size(), "queue", rQueue));
            runContext.logger().info("Consumed {} message(s) from MNS queue '{}'", handles.size(), rQueue);

            return Output.builder()
                .count(handles.size())
                .uri(uri)
                .build();
        }
    }

    private static List<String> receiveInto(RunContext runContext, File file, CloudQueue queue, String rQueue, SerdeType rSerdeType, int rMaxRecords, Instant deadline) throws IOException {
        var handles = new ArrayList<String>();
        try (var output = new BufferedOutputStream(new FileOutputStream(file))) {
            while (handles.size() < rMaxRecords && Instant.now().isBefore(deadline)) {
                var messages = receive(queue, rQueue, Math.min(BATCH_SIZE, rMaxRecords - handles.size()), 1);
                if (messages.isEmpty()) {
                    break;
                }
                for (var message : messages) {
                    FileSerde.write(output, deserialize(runContext, rSerdeType, message, rQueue));
                    handles.add(message.getReceiptHandle());
                }
            }
        }
        return handles;
    }

    // called once the file is stored, so a message only leaves the queue when it is safe in internal storage
    private static void deleteAll(RunContext runContext, CloudQueue queue, String rQueue, List<String> handles) {
        for (var i = 0; i < handles.size(); i += BATCH_SIZE) {
            try {
                delete(queue, rQueue, handles.subList(i, Math.min(i + BATCH_SIZE, handles.size())));
            } catch (IllegalStateException e) {
                runContext.logger().warn(e.getMessage());
            }
        }
    }

    static Object deserialize(RunContext runContext, SerdeType serdeType, com.aliyun.mns.model.Message message, String queue) {
        var body = message.getMessageBodyAsRawString();
        try {
            return serdeType.deserialize(body);
        } catch (IOException e) {
            runContext.logger().warn("MNS message {} from queue '{}' is not valid {}, keeping it as a string: {}", message.getMessageId(), queue, serdeType, e.getMessage());
            return body;
        }
    }

    static List<com.aliyun.mns.model.Message> receive(CloudQueue queue, String name, int batchSize, int waitSeconds) {
        try {
            var messages = queue.batchPopMessage(batchSize, waitSeconds);
            return messages == null ? List.of() : messages;
        } catch (ServiceException e) {
            if (MESSAGE_NOT_EXIST.equals(e.getErrorCode())) {
                return List.of();
            }
            throw new IllegalStateException(
                "Unable to receive from MNS queue '" + name + "' (" + e.getErrorCode() + "): " + hint(e.getErrorCode(), "mns:BatchReceiveMessage"), e
            );
        } catch (ServiceHandlingRequiredException e) {
            throw new IllegalStateException(
                "Unable to receive from MNS queue '" + name + "' (" + e.getErrorCode() + "): " + hint(e.getErrorCode(), "mns:BatchReceiveMessage"), e
            );
        } catch (ClientException e) {
            throw new IllegalStateException("Unable to reach MNS for queue '" + name + "': " + hint(null, null), e);
        }
    }

    static void delete(CloudQueue queue, String name, List<String> handles) {
        try {
            queue.batchDeleteMessage(handles);
        } catch (BatchDeleteException e) {
            throw new IllegalStateException(
                "Unable to delete " + e.getErrorMessages().size() + " message(s) from MNS queue '" + name + "', they will be delivered again: " +
                    e.getErrorMessages().values().stream().map(m -> m.getErrorCode() + ": " + m.getErrorMessage()).toList(), e
            );
        } catch (ServiceException e) {
            throw new IllegalStateException(
                "Unable to delete messages from MNS queue '" + name + "' (" + e.getErrorCode() + "), they will be delivered again: " + hint(e.getErrorCode(), "mns:BatchDeleteMessage"), e
            );
        } catch (ServiceHandlingRequiredException e) {
            throw new IllegalStateException(
                "Unable to delete messages from MNS queue '" + name + "' (" + e.getErrorCode() + "), they will be delivered again: " + hint(e.getErrorCode(), "mns:BatchDeleteMessage"), e
            );
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of messages consumed")
        private final Integer count;

        @Schema(title = "URI of the ION file holding the messages in internal storage, unset when no message was received")
        private final URI uri;
    }
}
