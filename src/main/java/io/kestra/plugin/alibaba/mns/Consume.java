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
import java.io.FileOutputStream;
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
    description = "Receives messages until the queue is empty, `maxRecords` is reached or `maxDuration` elapses, stores them in an ION file in internal storage and, by default, deletes them from the queue."
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
                    maxDuration: PT1M
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

    @Schema(
        title = "Max records",
        description = "Stop after consuming this many messages."
    )
    @PluginProperty(group = "execution")
    private Property<Integer> maxRecords;

    @Schema(
        title = "Max duration",
        description = "Stop after this duration elapses."
    )
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration;

    @Schema(
        title = "Serde type",
        description = "`STRING` keeps message bodies as text, `JSON` parses them."
    )
    @NotNull
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<SerdeType> serdeType = Property.ofValue(SerdeType.STRING);

    @Schema(
        title = "Auto-delete",
        description = "Delete messages from the queue once they are stored. When false, they become visible again after the queue's visibility timeout."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Boolean> autoDelete = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        if (this.maxRecords == null && this.maxDuration == null) {
            throw new IllegalArgumentException("`maxRecords` or `maxDuration` must be set to bound the consumption");
        }

        var rQueue = rQueue(runContext);
        var rMaxRecords = runContext.render(this.maxRecords).as(Integer.class).orElse(Integer.MAX_VALUE);
        var rMaxDuration = runContext.render(this.maxDuration).as(Duration.class).orElse(null);
        var rSerdeType = runContext.render(this.serdeType).as(SerdeType.class).orElse(SerdeType.STRING);
        var rAutoDelete = runContext.render(this.autoDelete).as(Boolean.class).orElse(true);
        var deadline = rMaxDuration == null ? null : Instant.now().plus(rMaxDuration);

        var total = 0;
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (var client = client(runContext); var output = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            var queue = client.queue(rQueue);
            while (total < rMaxRecords && (deadline == null || Instant.now().isBefore(deadline))) {
                var messages = receive(queue, rQueue, Math.min(BATCH_SIZE, rMaxRecords - total), 1);
                if (messages.isEmpty()) {
                    break;
                }

                var handles = new ArrayList<String>(messages.size());
                for (var message : messages) {
                    FileSerde.write(output, rSerdeType.deserialize(message.getMessageBodyAsRawString()));
                    total++;
                    handles.add(message.getReceiptHandle());
                }
                if (rAutoDelete) {
                    delete(queue, rQueue, handles);
                }
            }
            output.flush();
        }

        runContext.metric(Counter.of("mns.consume.messages", total, "queue", rQueue));
        runContext.logger().info("Consumed {} message(s) from MNS queue '{}'", total, rQueue);

        return Output.builder()
            .count(total)
            .uri(runContext.storage().putFile(tempFile))
            .build();
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
                "Unable to receive from MNS queue '" + name + "' (" + e.getErrorCode() + "): " +
                    "check that the queue exists in this region and account, and that the credentials have the mns:BatchReceiveMessage permission", e
            );
        } catch (ServiceHandlingRequiredException e) {
            throw new IllegalStateException(
                "Unable to receive from MNS queue '" + name + "' (" + e.getErrorCode() + "): check the queue configuration and the credentials", e
            );
        } catch (ClientException e) {
            throw new IllegalStateException(
                "Unable to reach MNS for queue '" + name + "': check `region`, `accountId` or `endpointOverride` and network access", e
            );
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
                "Unable to delete messages from MNS queue '" + name + "' (" + e.getErrorCode() + "), they will be delivered again: " +
                    "check that the credentials have the mns:BatchDeleteMessage permission", e
            );
        } catch (ServiceHandlingRequiredException e) {
            throw new IllegalStateException(
                "Unable to delete messages from MNS queue '" + name + "' (" + e.getErrorCode() + "), they will be delivered again", e
            );
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of messages consumed")
        private final Integer count;

        @Schema(title = "URI of the ION file holding the messages in internal storage")
        private final URI uri;
    }
}
