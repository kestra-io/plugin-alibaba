package io.kestra.plugin.alibaba.sls;

import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.LogContent;
import com.aliyun.openservices.log.common.LogItem;
import com.aliyun.openservices.log.common.QueriedLog;
import com.aliyun.openservices.log.request.GetLogsRequest;
import com.aliyun.openservices.log.response.GetLogsResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.alibaba.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Query logs from Alibaba Cloud Simple Log Service (SLS)",
    description = "Queries a Logstore within a project over a specified time range, supporting fetch and store output modes."
)
@Plugin(
    examples = {
        @Example(
            title = "Query error logs from an SLS logstore",
            full = true,
            code = """
                id: sls_query
                namespace: company.team

                tasks:
                  - id: query_logs
                    type: io.kestra.plugin.alibaba.sls.Query
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    project: ops-logs
                    logstore: app-events
                    query: "level: ERROR"
                    fetchType: FETCH
                """
        )
    }
)
public class Query extends AbstractConnection implements RunnableTask<Query.Output> {
    @NotNull
    @Schema(title = "Project", description = "The SLS project name.")
    @PluginProperty(group = "main")
    private Property<String> project;

    @NotNull
    @Schema(title = "Logstore", description = "The SLS Logstore name.")
    @PluginProperty(group = "main")
    private Property<String> logstore;

    @Schema(title = "Query", description = "Search query or SQL analysis statement. Defaults to empty string.")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<String> query = Property.ofValue("");

    @Schema(title = "Topic", description = "The log topic filter. Defaults to empty string.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> topic = Property.ofValue("");

    @Schema(title = "From", description = "Start of time range as ISO instant or epoch seconds. Defaults to 15 minutes ago.")
    @PluginProperty(group = "main")
    private Property<String> from;

    @Schema(title = "To", description = "End of time range as ISO instant or epoch seconds. Defaults to current time.")
    @PluginProperty(group = "main")
    private Property<String> to;

    @Builder.Default
    @Schema(title = "Offset", description = "Line offset for pagination; defaults to 0.")
    @PluginProperty(group = "processing")
    private Property<Integer> offset = Property.ofValue(0);

    @Builder.Default
    @Schema(title = "Lines", description = "Maximum number of log lines to return; defaults to 100, max 100.")
    @PluginProperty(group = "processing")
    private Property<Integer> lines = Property.ofValue(100);

    @Builder.Default
    @Schema(title = "Reverse", description = "Sort in reverse chronological order; defaults to false.")
    @PluginProperty(group = "processing")
    private Property<Boolean> reverse = Property.ofValue(false);

    @Builder.Default
    @Schema(title = "Result handling mode", description = "FETCH, FETCH_ONE, STORE, or NONE. Defaults to FETCH.")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rAccessKeyId = renderedAccessKeyId(runContext);
        String rAccessKeySecret = renderedAccessKeySecret(runContext);
        String rSecurityToken = renderedSecurityToken(runContext);
        String rRegion = renderedRegion(runContext);
        String rEndpoint = renderedEndpoint(runContext);
        String rProject = runContext.render(this.project).as(String.class).orElseThrow();
        String rLogstore = runContext.render(this.logstore).as(String.class).orElseThrow();
        String rQuery = runContext.render(this.query).as(String.class).orElse("");
        String rTopic = runContext.render(this.topic).as(String.class).orElse("");
        int rOffset = runContext.render(this.offset).as(Integer.class).orElse(0);
        int rLines = runContext.render(this.lines).as(Integer.class).orElse(100);
        boolean rReverse = runContext.render(this.reverse).as(Boolean.class).orElse(false);
        FetchType rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

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

        int toEpoch = parseEpochSecond(runContext.render(this.to).as(String.class).orElse(null), (int) Instant.now().getEpochSecond());
        int fromEpoch = parseEpochSecond(runContext.render(this.from).as(String.class).orElse(null), toEpoch - 900);

        GetLogsRequest request = new GetLogsRequest(rProject, rLogstore, fromEpoch, toEpoch, rTopic, rQuery, rOffset, rLines, rReverse);

        Instant start = Instant.now();
        GetLogsResponse response = client.GetLogs(request);
        Instant end = Instant.now();
        runContext.metric(Timer.of("duration", Duration.between(start, end)));

        List<QueriedLog> queriedLogs = response.GetLogs();
        List<Map<String, Object>> rows = new ArrayList<>(queriedLogs.size());

        for (QueriedLog qLog : queriedLogs) {
            LogItem item = qLog.GetLogItem();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("__time__", item.GetTime());
            map.put("__source__", qLog.GetSource());
            for (LogContent content : item.GetLogContents()) {
                map.put(content.GetKey(), content.GetValue());
            }
            rows.add(map);
        }

        long count = rows.size();
        runContext.metric(Counter.of("total.rows", count));

        Output.OutputBuilder builder = Output.builder()
            .count(count);

        if (FetchType.NONE.equals(rFetchType)) {
            return builder.build();
        }

        if (FetchType.FETCH.equals(rFetchType)) {
            builder.logs(rows);
        } else if (FetchType.FETCH_ONE.equals(rFetchType)) {
            builder.log(rows.isEmpty() ? Collections.emptyMap() : rows.get(0));
        } else if (FetchType.STORE.equals(rFetchType)) {
            File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
            try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                Long lineCount = FileSerde.writeAll(output, Flux.fromIterable(rows)).block();
                output.flush();
                URI uri = runContext.storage().putFile(tempFile);
                builder.uri(uri).count(lineCount);
            }
        }

        return builder.build();
    }

    private int parseEpochSecond(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            try {
                return (int) Instant.parse(value).getEpochSecond();
            } catch (Exception ex) {
                return defaultValue;
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Fetched logs as a list of key-value maps", description = "Only populated if fetchType is FETCH")
        private List<Map<String, Object>> logs;

        @Schema(title = "First fetched log item as a key-value map", description = "Only populated if fetchType is FETCH_ONE")
        private Map<String, Object> log;

        @Schema(title = "Total number of log records returned")
        private Long count;

        @Schema(title = "URI of the stored results in internal storage", description = "Only populated if fetchType is STORE")
        private URI uri;
    }
}
