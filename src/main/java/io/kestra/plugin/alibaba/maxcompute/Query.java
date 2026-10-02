package io.kestra.plugin.alibaba.maxcompute;

import com.aliyun.odps.Instance;
import com.aliyun.odps.Odps;
import com.aliyun.odps.account.Account;
import com.aliyun.odps.account.AliyunAccount;
import com.aliyun.odps.account.StsAccount;
import com.aliyun.odps.data.Record;
import com.aliyun.odps.task.SQLTask;
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
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Execute a MaxCompute SQL query",
    description = "Runs a SQL statement on Alibaba Cloud MaxCompute and retrieves or stores the result set. Supports cancellation via instance termination."
)
@Plugin(
    examples = {
        @Example(
            title = "Run a MaxCompute query and store the result",
            full = true,
            code = """
                id: maxcompute_query
                namespace: company.team

                tasks:
                  - id: query
                    type: io.kestra.plugin.alibaba.maxcompute.Query
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-shanghai
                    project: analytics
                    sql: |
                      SELECT region, SUM(amount) AS total
                      FROM orders
                      WHERE ds = '{{ now() | date('yyyyMMdd') }}'
                      GROUP BY region
                    fetchType: STORE
                """
        ),
        @Example(
            title = "Execute a query and fetch rows",
            full = true,
            code = """
                id: maxcompute_fetch
                namespace: company.team

                tasks:
                  - id: fetch_data
                    type: io.kestra.plugin.alibaba.maxcompute.Query
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    project: my_project
                    sql: "SELECT id, name FROM users LIMIT 100"
                    fetchType: FETCH
                """
        )
    }
)
public class Query extends AbstractConnection implements RunnableTask<Query.Output> {
    @NotNull
    @Schema(
        title = "Project",
        description = "The MaxCompute project name."
    )
    @PluginProperty(group = "main")
    private Property<String> project;

    @NotNull
    @Schema(
        title = "SQL query",
        description = "The SQL query to execute."
    )
    @PluginProperty(group = "main")
    private Property<String> sql;

    @Builder.Default
    @Schema(
        title = "Result handling mode",
        description = "FETCH_ONE, FETCH, STORE, or NONE. Defaults to NONE."
    )
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.NONE);

    @Schema(
        title = "Tunnel endpoint override",
        description = "Custom tunnel endpoint URL. If omitted, default tunnel routing is used."
    )
    @PluginProperty(group = "advanced")
    private Property<String> tunnelEndpoint;

    @Getter(AccessLevel.NONE)
    private final transient AtomicReference<Instance> instanceRef = new AtomicReference<>();

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rAccessKeyId = renderedAccessKeyId(runContext);
        String rAccessKeySecret = renderedAccessKeySecret(runContext);
        String rSecurityToken = renderedSecurityToken(runContext);
        String rRegion = renderedRegion(runContext);
        String rEndpoint = renderedEndpoint(runContext);
        String rProject = runContext.render(this.project).as(String.class).orElseThrow();
        String rSql = runContext.render(this.sql).as(String.class).orElseThrow();
        FetchType rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.NONE);

        if (rEndpoint == null || rEndpoint.isBlank()) {
            rEndpoint = "https://service." + rRegion + ".maxcompute.aliyun.com/api";
        }

        Account account;
        if (rSecurityToken != null && !rSecurityToken.isBlank()) {
            account = new StsAccount(rAccessKeyId, rAccessKeySecret, rSecurityToken);
        } else {
            account = new AliyunAccount(rAccessKeyId, rAccessKeySecret);
        }

        Odps odps = new Odps(account);
        odps.setEndpoint(rEndpoint);
        odps.setDefaultProject(rProject);

        String rTunnelEndpoint = runContext.render(this.tunnelEndpoint).as(String.class).orElse(null);
        if (rTunnelEndpoint != null && !rTunnelEndpoint.isBlank()) {
            odps.getTunnelEndpoint(); // warm-up
        }

        String taskName = "kestra_" + (runContext.flowInfo() != null ? runContext.flowInfo().id() : "flow") + "_" + UUID.randomUUID().toString().replace("-", "");

        runContext.logger().info("Executing MaxCompute SQL query on project '{}': {}", rProject, rSql);

        Instant start = Instant.now();
        Instance instance = SQLTask.run(odps, rProject, rSql, taskName, null, null);
        instanceRef.set(instance);

        instance.waitForSuccess();
        Instant end = Instant.now();
        runContext.metric(Timer.of("duration", Duration.between(start, end)));

        Output.OutputBuilder builder = Output.builder()
            .instanceId(instance.getId());

        if (FetchType.NONE.equals(rFetchType)) {
            builder.size(0L);
            return builder.build();
        }

        List<Record> records = SQLTask.getResult(instance);
        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        for (Record record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < record.getColumnCount(); i++) {
                String colName = record.getColumns()[i].getName();
                Object val = record.get(i);
                if (val instanceof Date date) {
                    val = date.toInstant().toString();
                }
                row.put(colName, val);
            }
            rows.add(row);
        }

        long count = rows.size();
        runContext.metric(Counter.of("total.rows", count));
        builder.size(count);

        if (FetchType.FETCH.equals(rFetchType)) {
            builder.rows(rows);
        } else if (FetchType.FETCH_ONE.equals(rFetchType)) {
            builder.row(rows.isEmpty() ? Collections.emptyMap() : rows.get(0));
        } else if (FetchType.STORE.equals(rFetchType)) {
            File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
            try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                Long lineCount = FileSerde.writeAll(output, Flux.fromIterable(rows)).block();
                output.flush();
                URI uri = runContext.storage().putFile(tempFile);
                builder.uri(uri).size(lineCount);
            }
        }

        return builder.build();
    }

    @Override
    public void kill() {
        try {
            Instance instance = instanceRef.get();
            if (instance != null && !instance.isTerminated()) {
                instance.stop();
            }
        } catch (Exception ignored) {
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The MaxCompute instance ID")
        private String instanceId;

        @Schema(title = "List containing fetched rows", description = "Only populated if fetchType is FETCH")
        private List<Map<String, Object>> rows;

        @Schema(title = "Map containing the first row", description = "Only populated if fetchType is FETCH_ONE")
        private Map<String, Object> row;

        @Schema(title = "The total number of rows returned")
        private Long size;

        @Schema(title = "The URI of the stored result in internal storage", description = "Only populated if fetchType is STORE")
        private URI uri;
    }
}
