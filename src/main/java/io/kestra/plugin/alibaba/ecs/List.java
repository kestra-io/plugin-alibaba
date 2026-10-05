package io.kestra.plugin.alibaba.ecs;

import com.aliyun.ecs20140526.models.DescribeInstancesRequest;
import com.aliyun.ecs20140526.models.DescribeInstancesResponseBody;
import com.aliyun.tea.TeaException;
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
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.FileWriter;
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
        description = "Only return instances in this status."
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
        var regionId = rRegion(runContext);
        var client = client(runContext, regionId);

        var ids = runContext.render(this.instanceIds).asList(String.class);
        var request = new DescribeInstancesRequest()
            .setRegionId(regionId)
            .setMaxResults(PAGE_SIZE)
            .setInstanceIds(ids.isEmpty() ? null : JacksonMapper.ofJson().writeValueAsString(ids))
            .setStatus(runContext.render(this.status).as(Status.class).map(Status::value).orElse(null))
            .setInstanceName(runContext.render(this.instanceName).as(String.class).orElse(null));

        var instances = new ArrayList<Map<String, Object>>();
        String nextToken = null;
        do {
            DescribeInstancesResponseBody body;
            try {
                body = client.describeInstancesWithOptions(request.setNextToken(nextToken), new RuntimeOptions()).getBody();
            } catch (TeaException e) {
                throw new IllegalStateException(
                    "Unable to list instances in " + regionId + " (" + e.getCode() + ": " + e.getMessage() + "): " +
                        "check the region and filters, and that the credentials have the ecs:DescribeInstances permission",
                    e
                );
            }
            if (body.getInstances() != null && body.getInstances().getInstance() != null) {
                body.getInstances().getInstance().forEach(i -> instances.add(i.toMap()));
            }
            nextToken = body.getNextToken();
        } while (nextToken != null && !nextToken.isEmpty());

        runContext.logger().debug("Found {} instance(s) in {}", instances.size(), regionId);

        var output = FetchOutput.builder().size((long) instances.size());
        switch (runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH)) {
            case FETCH -> output.rows(new ArrayList<>(instances));
            case FETCH_ONE -> output.row(instances.isEmpty() ? null : instances.getFirst());
            case STORE -> {
                var file = runContext.workingDir().createTempFile(".ion").toFile();
                try (var writer = new BufferedWriter(new FileWriter(file), FileSerde.BUFFER_SIZE)) {
                    FileSerde.writeAll(writer, Flux.fromIterable(instances)).block();
                }
                output.uri(runContext.storage().putFile(file));
            }
            case NONE -> {
            }
        }
        return output.build();
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
