package io.kestra.plugin.alibaba.mns;

import com.aliyun.mns.common.ServiceException;
import com.aliyun.mns.common.ServiceHandlingRequiredException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.RealtimeTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.alibaba.mns.model.Message;
import io.kestra.plugin.alibaba.mns.model.SerdeType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow for each MNS queue message",
    description = "Long-polls an MNS queue and starts one execution per message as soon as it arrives, with the message body available as `trigger.data`. Use `Trigger` to process messages in batches."
)
@Plugin(
    examples = {
        @Example(
            title = "Log each order as it arrives",
            full = true,
            code = """
                id: mns_realtime
                namespace: company.team

                triggers:
                  - id: on_message
                    type: io.kestra.plugin.alibaba.mns.RealtimeTrigger
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    accountId: "{{ secret('ALIBABA_ACCOUNT_ID') }}"
                    queue: orders
                    serdeType: JSON

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Order {{ trigger.data.orderId }}"
                """
        )
    }
)
public class RealtimeTrigger extends AbstractMnsTrigger implements RealtimeTriggerInterface, TriggerOutput<Message> {
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(30);
    private static final Duration SLEEP_SLICE = Duration.ofMillis(200);
    private static final Set<String> FATAL_ERROR_CODES = Set.of("AccessDenied", "QueueNotExist", "InvalidAccessKeyId", "SignatureDoesNotMatch");

    @Schema(
        title = "Wait time",
        description = "How long each long-poll request waits for messages, up to 30 seconds."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Duration> waitTime = Property.ofValue(Duration.ofSeconds(20));

    @Schema(
        title = "Batch size",
        description = "Maximum number of messages fetched per request, from 1 to 16. Each message still starts its own execution."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Integer> batchSize = Property.ofValue(Consume.BATCH_SIZE);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final CountDownLatch waitForTermination = new CountDownLatch(1);

    @Override
    public Publisher<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var task = consumeTask().build();

        return publisher(task, runContext)
            .map(message -> TriggerService.generateRealtimeExecution(this, conditionContext, context, message));
    }

    Flux<Message> publisher(Consume task, RunContext runContext) {
        return Flux.create(sink -> {
            var logger = runContext.logger();
            var signalledError = false;
            try (var client = task.client(runContext)) {
                var rQueue = task.rQueue(runContext);
                var queue = client.queue(rQueue);
                var rWaitSeconds = (int) Math.min(30, runContext.render(this.waitTime).as(Duration.class).orElse(Duration.ofSeconds(20)).toSeconds());
                var rBatchSize = Math.max(1, Math.min(Consume.BATCH_SIZE, runContext.render(this.batchSize).as(Integer.class).orElse(Consume.BATCH_SIZE)));
                var rSerdeType = runContext.render(this.serdeType).as(SerdeType.class).orElse(SerdeType.STRING);
                var rAutoDelete = runContext.render(this.autoDelete).as(Boolean.class).orElse(true);

                logger.info("Starting MNS consumption from queue '{}'", rQueue);
                var backoff = BACKOFF_BASE;
                while (isActive.get()) {
                    try {
                        var messages = Consume.receive(queue, rQueue, rBatchSize, rWaitSeconds);
                        backoff = BACKOFF_BASE;

                        var handles = new ArrayList<String>(messages.size());
                        for (var message : messages) {
                            Object body;
                            try {
                                body = rSerdeType.deserialize(message.getMessageBodyAsRawString());
                            } catch (IOException e) {
                                logger.warn("Unable to deserialize MNS message {}, emitting it as a string: {}", message.getMessageId(), e.getMessage());
                                body = message.getMessageBodyAsRawString();
                            }
                            sink.next(Message.builder().data(body).build());
                            handles.add(message.getReceiptHandle());
                        }

                        if (rAutoDelete && !handles.isEmpty()) {
                            try {
                                Consume.delete(queue, rQueue, handles);
                            } catch (IllegalStateException e) {
                                logger.warn(e.getMessage());
                            }
                        }
                    } catch (IllegalStateException e) {
                        if (FATAL_ERROR_CODES.contains(errorCode(e.getCause()))) {
                            logger.error("Stopping the trigger: {}", e.getMessage());
                            signalledError = true;
                            isActive.set(false);
                            sink.error(e);
                        } else {
                            logger.warn("{}. Retrying in {}", e.getMessage(), backoff);
                            sleep(backoff);
                            backoff = backoff.multipliedBy(2).compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : backoff.multipliedBy(2);
                        }
                    }
                }
            } catch (Throwable e) {
                signalledError = true;
                sink.error(e);
            } finally {
                if (!signalledError) {
                    sink.complete();
                }
                this.waitForTermination.countDown();
            }
        });
    }

    private static String errorCode(Throwable cause) {
        if (cause instanceof ServiceException e) {
            return e.getErrorCode();
        }
        if (cause instanceof ServiceHandlingRequiredException e) {
            return e.getErrorCode();
        }
        return null;
    }

    @Override
    public void kill() {
        stop(true);
    }

    @Override
    public void stop() {
        stop(false);
    }

    private void stop(boolean wait) {
        if (!isActive.compareAndSet(true, false)) {
            return;
        }
        if (wait) {
            try {
                this.waitForTermination.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sleep(Duration delay) {
        var remaining = delay.toMillis();
        while (isActive.get() && remaining > 0) {
            var slice = Math.min(remaining, SLEEP_SLICE.toMillis());
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                isActive.set(false);
                return;
            }
            remaining -= slice;
        }
    }
}
