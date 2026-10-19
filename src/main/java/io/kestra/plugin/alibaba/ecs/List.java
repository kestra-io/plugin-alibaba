package io.kestra.plugin.alibaba.ecs;

import com.aliyun.ecs20140526.Client;
import com.aliyun.ecs20140526.models.DescribeInstancesRequest;
import com.aliyun.ecs20140526.models.DescribeInstancesResponseBody;
import com.aliyun.tea.TeaException;
import com.aliyun.tea.TeaUnretryableException;
import com.aliyun.teautil.models.RuntimeOptions;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List ECS instances",
    description = "Lists the ECS instances of a region, optionally filtered by IDs, status or name. Each instance is returned with the fields of the DescribeInstances API, for example `InstanceId`, `InstanceName` and `Status`."
)
@Plugin(
    examples = {
        @Example(
            title = "List running instances and log their IDs",
            full = true,
            code = """
                id: ecs_list
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.alibaba.ecs.List
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    status: RUNNING
                    fetchType: FETCH

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.list.rows | jq('.[].InstanceId') }}"
                """
        ),
        @Example(
            title = "Store all instances of a region in internal storage",
            full = true,
            code = """
                id: ecs_list_store
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.alibaba.ecs.List
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractEcs implements RunnableTask<FetchOutput> {
    private static final int PAGE_SIZE = 100;

    @Schema(
        title = "Instance IDs",
        description = "Only return these instances, up to 100 IDs."
    )
    @PluginProperty(group = "main")
    private Property<java.util.List<String>> instanceIds;

    @Schema(
        title = "Status",
        description = "Only return instances in this status. These are all the values accepted by the DescribeInstances `Status` filter."
    )
    @PluginProperty(group = "main")
    private Property<Status> status;

    @Schema(
        title = "Instance name",
        description = "Only return instances with this name. Supports the `*` wildcard, for example `web-*`."
    )
    @PluginProperty(group = "main")
    private Property<String> instanceName;

    @Schema(
        title = "Fetch type",
        description = "`FETCH` outputs all instances as `rows`, `FETCH_ONE` outputs the first one as `row`, `STORE` writes them to an ION file in internal storage and outputs its `uri`, `NONE` only outputs the `size`."
    )
    @NotNull
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var rRegion = requiredRegion(runContext);
        var rInstanceIds = runContext.render(this.instanceIds).asList(String.class);
        checkInstanceIdCount(rInstanceIds);
        var rStatus = runContext.render(this.status).as(Status.class).map(Status::value).orElse(null);
        var rInstanceName = runContext.render(this.instanceName).as(String.class).orElse(null);
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        var client = client(runContext, rRegion);
        var options = runtimeOptions(runContext);
        var request = new DescribeInstancesRequest()
            .setRegionId(rRegion)
            .setMaxResults(PAGE_SIZE)
            .setInstanceIds(rInstanceIds.isEmpty() ? null : JacksonMapper.ofJson().writeValueAsString(rInstanceIds))
            .setStatus(rStatus)
            .setInstanceName(rInstanceName);

        var output = FetchOutput.builder();
        switch (rFetchType) {
            case FETCH -> {
                var rows = new ArrayList<Object>();
                output.size(forEachInstance(client, request, options, rRegion, rows::add)).rows(rows);
            }
            case FETCH_ONE -> {
                var rows = new ArrayList<Map<String, Object>>();
                output.size(forEachInstance(client, request, options, rRegion, instance -> {
                    rows.add(instance);
                    return false;
                })).row(rows.isEmpty() ? null : rows.getFirst());
            }
            case STORE -> {
                var file = runContext.workingDir().createTempFile(".ion").toFile();
                try (var stream = new BufferedOutputStream(new FileOutputStream(file))) {
                    output.size(forEachInstance(client, request, options, rRegion, instance -> {
                        FileSerde.write(stream, instance);
                        return true;
                    }));
                }
                output.uri(runContext.storage().putFile(file));
            }
            case NONE -> output.size(forEachInstance(client, request, options, rRegion, instance -> true));
        }
        return output.build();
    }

    private long forEachInstance(Client client, DescribeInstancesRequest request, RuntimeOptions options, String rRegion, InstanceHandler handler) throws Exception {
        var count = 0L;
        String nextToken = null;
        do {
            var body = describe(client, request.setNextToken(nextToken), options, rRegion);
            var page = body.getInstances() == null || body.getInstances().getInstance() == null
                ? java.util.List.<DescribeInstancesResponseBody.DescribeInstancesResponseBodyInstancesInstance>of()
                : body.getInstances().getInstance();
            for (var instance : page) {
                count++;
                if (!handler.accept(instance.toMap())) {
                    return count;
                }
            }
            nextToken = body.getNextToken();
        } while (nextToken != null && !nextToken.isEmpty());
        return count;
    }

    private static DescribeInstancesResponseBody describe(Client client, DescribeInstancesRequest request, RuntimeOptions options, String rRegion) throws Exception {
        try {
            return client.describeInstancesWithOptions(request, options).getBody();
        } catch (TeaException e) {
            throw new IllegalStateException(
                "Unable to list instances in " + rRegion + " (" + e.getCode() + ": " + e.getMessage() + "): " +
                    "check the region and filters, and that the credentials have the ecs:DescribeInstances permission",
                e
            );
        } catch (TeaUnretryableException e) {
            throw new IllegalStateException(
                "No response when listing instances in " + rRegion + " (" + e.getMessage() + "): check network access to the ECS endpoint, or raise the task `timeout`",
                e
            );
        }
    }

    @FunctionalInterface
    private interface InstanceHandler {
        // false stops paging
        boolean accept(Map<String, Object> instance) throws IOException;
    }

    public enum Status {
        PENDING("Pending"),
        RUNNING("Running"),
        STARTING("Starting"),
        STOPPING("Stopping"),
        STOPPED("Stopped");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }
}
