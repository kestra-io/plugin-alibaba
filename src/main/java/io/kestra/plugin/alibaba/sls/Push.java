package io.kestra.plugin.alibaba.sls;

import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.LogItem;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.alibaba.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Push logs to Alibaba Cloud Simple Log Service (SLS)",
    description = "Writes log records to an SLS Logstore from an inline list of maps, a single map, or a file in Kestra internal storage."
)
@Plugin(
    examples = {
        @Example(
            title = "Push inline logs to SLS",
            full = true,
            code = """
                id: sls_push
                namespace: company.team

                tasks:
                  - id: push_logs
                    type: io.kestra.plugin.alibaba.sls.Push
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    project: ops-logs
                    logstore: app-events
                    from:
                      - level: INFO
                        message: Flow finished
                        flow: "{{ flow.id }}"
                """
        )
    }
)
public class Push extends AbstractConnection implements RunnableTask<Push.Output> {
    @NotNull
    @Schema(title = "Project", description = "The SLS project name.")
    @PluginProperty(group = "main")
    private Property<String> project;

    @NotNull
    @Schema(title = "Logstore", description = "The SLS Logstore name.")
    @PluginProperty(group = "main")
    private Property<String> logstore;

    @Schema(title = "Topic", description = "The log topic.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> topic = Property.ofValue("");

    @Schema(title = "Source", description = "The log source. Defaults to machine hostname or 'kestra'.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> source = Property.ofValue("kestra");

    @NotNull
    @Schema(
        title = "Source data",
        description = "List of maps, single map, or URI pointing to a JSON/ION file in Kestra internal storage."
    )
    @PluginProperty(group = "main")
    private Property<Object> from;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rAccessKeyId = renderedAccessKeyId(runContext);
        String rAccessKeySecret = renderedAccessKeySecret(runContext);
        String rSecurityToken = renderedSecurityToken(runContext);
        String rRegion = renderedRegion(runContext);
        String rEndpoint = renderedEndpoint(runContext);
        String rProject = runContext.render(this.project).as(String.class).orElseThrow();
        String rLogstore = runContext.render(this.logstore).as(String.class).orElseThrow();
        String rTopic = runContext.render(this.topic).as(String.class).orElse("");
        String rSource = runContext.render(this.source).as(String.class).orElse("kestra");

        com.aliyun.openservices.log.http.client.ClientConfiguration clientConfig = new com.aliyun.openservices.log.http.client.ClientConfiguration();
        String endpoint = rEndpoint;
        if (endpoint != null && !endpoint.isBlank()) {
            try {
                URI uri = URI.create(endpoint.startsWith("http://") || endpoint.startsWith("https://") ? endpoint : "http://" + endpoint);
                if (uri.getPort() != -1) {
                    clientConfig.setProxyHost(uri.getHost());
                    clientConfig.setProxyPort(uri.getPort());
                    endpoint = (uri.getScheme() != null ? uri.getScheme() : "http") + "://log.aliyuncs.com";
                }
            } catch (Exception ignored) {
            }
        } else {
            endpoint = (rRegion != null ? rRegion : "cn-hangzhou") + ".log.aliyuncs.com";
        }

        Client client;
        if (rSecurityToken != null && !rSecurityToken.isBlank()) {
            client = new Client(endpoint, rAccessKeyId, rAccessKeySecret, rSecurityToken, clientConfig);
        } else {
            client = new Client(endpoint, rAccessKeyId, rAccessKeySecret, clientConfig);
        }

        Object renderedFrom = runContext.render(this.from).as(Object.class).orElseThrow();
        List<Map<String, Object>> records = new ArrayList<>();

        if (renderedFrom instanceof String uriString && uriString.startsWith("kestra://")) {
            URI uri = URI.create(uriString);
            try (InputStream is = runContext.storage().getFile(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                FileSerde.readAll(reader, Map.class).toStream().forEach(obj -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) obj;
                    records.add(map);
                });
            }
        } else if (renderedFrom instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> casted = (Map<String, Object>) map;
                    records.add(casted);
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = JacksonMapper.ofJson().convertValue(item, Map.class);
                    records.add(map);
                }
            }
        } else if (renderedFrom instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) map;
            records.add(casted);
        }

        List<LogItem> logItems = new ArrayList<>();
        int currentEpoch = (int) Instant.now().getEpochSecond();

        for (Map<String, Object> record : records) {
            int logTime = currentEpoch;
            if (record.containsKey("__time__")) {
                try {
                    logTime = Integer.parseInt(String.valueOf(record.get("__time__")));
                } catch (Exception ignored) {
                }
            } else if (record.containsKey("timestamp")) {
                try {
                    logTime = (int) Instant.parse(String.valueOf(record.get("timestamp"))).getEpochSecond();
                } catch (Exception ignored) {
                }
            }

            LogItem item = new LogItem(logTime);
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                String key = entry.getKey();
                if (!"__time__".equals(key)) {
                    item.PushBack(key, entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                }
            }
            logItems.add(item);
        }

        // SLS recommends batching max 4096 logs per PutLogs request
        int batchSize = 4096;
        for (int i = 0; i < logItems.size(); i += batchSize) {
            List<LogItem> batch = logItems.subList(i, Math.min(i + batchSize, logItems.size()));
            client.PutLogs(rProject, rLogstore, rTopic, batch, rSource);
        }

        long count = logItems.size();
        runContext.logger().info("Pushed {} log items to SLS project '{}' logstore '{}'", count, rProject, rLogstore);
        runContext.metric(Counter.of("pushed.logs", count));

        return Output.builder()
            .count(count)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The total number of log records pushed")
        private final Long count;
    }
}
