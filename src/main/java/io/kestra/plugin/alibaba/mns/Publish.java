package io.kestra.plugin.alibaba.mns;

import com.aliyun.mns.common.BatchSendException;
import com.aliyun.mns.common.ClientException;
import com.aliyun.mns.common.ServiceException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Data;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.alibaba.mns.model.Message;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.kestra.core.utils.Rethrow.throwConsumer;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Publish messages to an MNS queue",
    description = "Sends one or more messages to an Alibaba Cloud Message Service (MNS) queue, in batches of 16. Messages come from an inline list or a `kestra://` ION file."
)
@Plugin(
    examples = {
        @Example(
            title = "Publish a message to a queue",
            full = true,
            code = """
                id: mns_publish
                namespace: company.team

                tasks:
                  - id: publish
                    type: io.kestra.plugin.alibaba.mns.Publish
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    accountId: "{{ secret('ALIBABA_ACCOUNT_ID') }}"
                    queue: orders
                    from:
                      - data: '{"orderId": 42}'
                      - data:
                          orderId: 43
                        delaySeconds: 60
                """
        ),
        @Example(
            title = "Publish every row of an internal storage file",
            full = true,
            code = """
                id: mns_publish_file
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE
                    description: An ION file with one `data` field per row

                tasks:
                  - id: publish
                    type: io.kestra.plugin.alibaba.mns.Publish
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    accountId: "{{ secret('ALIBABA_ACCOUNT_ID') }}"
                    queue: orders
                    from: "{{ inputs.file }}"
                """
        )
    },
    metrics = {
        @Metric(
            name = "mns.publish.messages",
            type = Counter.TYPE,
            unit = "messages",
            description = "Number of messages published to the MNS queue."
        )
    }
)
public class Publish extends AbstractMns implements RunnableTask<Publish.Output>, Data.From {
    private static final int BATCH_SIZE = 16;

    @Schema(
        title = Data.From.TITLE,
        description = Data.From.DESCRIPTION,
        anyOf = {String.class, List.class, Message.class}
    )
    @NotNull
    @PluginProperty(group = "source")
    private Object from;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rQueue = rQueue(runContext);

        try (var client = client(runContext)) {
            var queue = client.queue(rQueue);
            var total = new AtomicInteger();

            Data.from(from)
                .readAs(runContext, Message.class, map -> JacksonMapper.toMap(map, Message.class))
                .buffer(BATCH_SIZE)
                .doOnNext(throwConsumer(batch -> {
                    var messages = new ArrayList<com.aliyun.mns.model.Message>(batch.size());
                    for (var message : batch) {
                        messages.add(message.toMns(runContext));
                    }
                    try {
                        queue.batchPutMessage(messages);
                    } catch (BatchSendException e) {
                        var failed = e.getMessages().stream()
                            .filter(com.aliyun.mns.model.Message::isErrorMessage)
                            .map(m -> m.getErrorMessageDetail().getErrorCode() + ": " + m.getErrorMessageDetail().getErrorMessage())
                            .toList();
                        throw new IllegalStateException(
                            "Unable to publish " + failed.size() + " of " + batch.size() + " messages to MNS queue '" + rQueue + "': " + failed, e
                        );
                    } catch (ServiceException e) {
                        throw new IllegalStateException(
                            "Unable to publish to MNS queue '" + rQueue + "' (" + e.getErrorCode() + "): " +
                                "check that the queue exists in this region and account, and that the credentials have the mns:BatchSendMessage permission", e
                        );
                    } catch (ClientException e) {
                        throw new IllegalStateException(
                            "Unable to reach MNS for queue '" + rQueue + "': check `region`, `accountId` or `endpointOverride` and network access", e
                        );
                    }
                    total.addAndGet(batch.size());
                }))
                .blockLast();

            runContext.metric(Counter.of("mns.publish.messages", total.get(), "queue", rQueue));
            runContext.logger().info("Published {} message(s) to MNS queue '{}'", total.get(), rQueue);

            return Output.builder()
                .messagesCount(total.get())
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of messages published")
        private final Integer messagesCount;
    }
}
