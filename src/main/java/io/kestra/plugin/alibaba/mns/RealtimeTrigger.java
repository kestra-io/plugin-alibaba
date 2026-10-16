package io.kestra.plugin.alibaba.mns;

import com.aliyun.mns.common.ServiceException;
import com.aliyun.mns.client.CloudQueue;
import com.aliyun.mns.common.ServiceHandlingRequiredException;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
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
import io.kestra.plugin.alibaba.mns.model.ReceivedMessage;
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
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow for each MNS queue message",
    description = "Long-polls an MNS queue and starts one execution per message, with the message body available as `trigger.data`. " +
        "It only polls when executions are requested, and deletes the messages of a poll once Kestra has taken them and requests more, so a stop or kill never deletes a message that was not handed over. " +
        "Use `Trigger` to process messages in batches."
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
public class RealtimeTrigger extends AbstractMnsTrigger implements RealtimeTriggerInterface, TriggerOutput<ReceivedMessage> {
    private static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(20);
    private static final Duration MIN_WAIT_TIME = Duration.ofSeconds(1);
    private static final Duration MAX_WAIT_TIME = Duration.ofSeconds(30);
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(30);
    private static final Duration SLEEP_SLICE = Duration.ofMillis(200);
    private static final Set<String> FATAL_ERROR_CODES = Set.of("AccessDenied", "QueueNotExist", "InvalidAccessKeyId", "SignatureDoesNotMatch");

    @Schema(
        title = "Wait time",
        description = "How long each long-poll request waits for messages, from 1 to 30 seconds, 20 seconds by default."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Duration> waitTime = Property.ofValue(DEFAULT_WAIT_TIME);

    @Schema(
        title = "Batch size",
        description = "Maximum number of messages fetched per request, from 1 to 16, 16 by default. Each message still starts its own execution."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Integer> batchSize = Property.ofValue(Consume.BATCH_SIZE);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    @Override
    public Publisher<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();

        return publisher(consumeTask().build(), runContext)
            .map(message -> TriggerService.generateRealtimeExecution(this, conditionContext, context, message));
    }

    Flux<ReceivedMessage> publisher(Consume task, RunContext runContext) {
        return Flux.create(sink -> {
            var signalledError = false;
            try (var client = task.client(runContext)) {
                var settings = settings(task, runContext);
                var queue = client.queue(settings.rQueue());
                runContext.logger().info("Starting MNS consumption from queue '{}'", settings.rQueue());

                var backoff = BACKOFF_BASE;
                var handedOver = new ArrayList<String>();
                while (isActive.get() && !sink.isCancelled()) {
                    // only poll when executions are requested, so received messages never pile up in memory
                    var requested = sink.requestedFromDownstream();
                    if (requested <= 0) {
                        sleep(SLEEP_SLICE);
                        continue;
                    }

                    ack(runContext, queue, settings, handedOver);
                    try {
                        handedOver.addAll(pollOnce(runContext, queue, settings, (int) Math.min(settings.rBatchSize(), requested), sink));
                        backoff = BACKOFF_BASE;
                    } catch (IllegalStateException e) {
                        var code = errorCode(e.getCause());
                        if (code != null && FATAL_ERROR_CODES.contains(code)) {
                            runContext.logger().error("Stopping the trigger: {}", e.getMessage());
                            signalledError = true;
                            isActive.set(false);
                            sink.error(e);
                        } else {
                            runContext.logger().warn("{}. Retrying in {}", e.getMessage(), backoff);
                            sleep(backoff);
                            backoff = nextBackoff(backoff);
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
            }
        });
    }

    private Settings settings(Consume task, RunContext runContext) throws IllegalVariableEvaluationException {
        var rWaitTime = runContext.render(this.waitTime).as(Duration.class).orElse(DEFAULT_WAIT_TIME);
        if (rWaitTime.compareTo(MIN_WAIT_TIME) < 0 || rWaitTime.compareTo(MAX_WAIT_TIME) > 0) {
            throw new IllegalArgumentException("`waitTime` must be between 1 and 30 seconds, got " + rWaitTime);
        }
        var rBatchSize = runContext.render(this.batchSize).as(Integer.class).orElse(Consume.BATCH_SIZE);
        if (rBatchSize < 1 || rBatchSize > Consume.BATCH_SIZE) {
            throw new IllegalArgumentException("`batchSize` must be between 1 and " + Consume.BATCH_SIZE + ", got " + rBatchSize);
        }
        return new Settings(
            task.rQueue(runContext),
            (int) rWaitTime.toSeconds(),
            rBatchSize,
            runContext.render(this.serdeType).as(SerdeType.class).orElse(SerdeType.STRING),
            runContext.render(this.autoDelete).as(Boolean.class).orElse(true)
        );
    }

    // messages never taken (stop, kill, failure) stay in the queue and are redelivered
    private static void ack(RunContext runContext, CloudQueue queue, Settings settings, List<String> handedOver) {
        if (settings.rAutoDelete() && !handedOver.isEmpty()) {
            try {
                Consume.delete(queue, settings.rQueue(), handedOver);
            } catch (RuntimeException e) {
                runContext.logger().warn("{}", e.getMessage());
            }
        }
        handedOver.clear();
    }

    private List<String> pollOnce(RunContext runContext, CloudQueue queue, Settings settings, int count, FluxSink<ReceivedMessage> sink) {
        var handles = new ArrayList<String>();
        for (var message : Consume.receive(queue, settings.rQueue(), count, settings.rWaitSeconds())) {
            // stopped during the long-poll: no execution, the message is redelivered
            if (!isActive.get() || sink.isCancelled()) {
                break;
            }
            sink.next(ReceivedMessage.builder()
                .messageId(message.getMessageId())
                .data(Consume.deserialize(runContext, settings.rSerdeType(), message, settings.rQueue()))
                .build());
            handles.add(message.getReceiptHandle());
        }
        return handles;
    }

    private static Duration nextBackoff(Duration backoff) {
        var doubled = backoff.multipliedBy(2);
        return doubled.compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : doubled;
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

    // never waits: the loop ends after the in-flight long-poll returns and then closes the client
    @Override
    public void kill() {
        isActive.set(false);
    }

    @Override
    public void stop() {
        isActive.set(false);
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

    private record Settings(String rQueue, int rWaitSeconds, int rBatchSize, SerdeType rSerdeType, boolean rAutoDelete) {
    }
}
