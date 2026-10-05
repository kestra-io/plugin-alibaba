package io.kestra.plugin.alibaba.ecs;

import com.aliyun.ecs20140526.Client;
import com.aliyun.ecs20140526.models.StopInstancesRequest;
import com.aliyun.tea.TeaModel;
import com.aliyun.teautil.models.RuntimeOptions;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Stop ECS instances",
    description = "Stops one or more running ECS instances. The task returns once Alibaba Cloud accepts the request, while instances are still `Stopping`."
)
@Plugin(
    examples = {
        @Example(
            title = "Stop an instance and stop billing for its compute resources",
            full = true,
            code = """
                id: ecs_stop
                namespace: company.team

                tasks:
                  - id: stop
                    type: io.kestra.plugin.alibaba.ecs.Stop
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    instanceIds:
                      - i-bp1example0000001
                    stoppedMode: STOP_CHARGING
                """
        )
    }
)
public class Stop extends AbstractInstanceAction {
    @Schema(
        title = "Force stop",
        description = "Stop the instances immediately, like a power cut. Data not written to disk may be lost."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Boolean> forceStop = Property.ofValue(false);

    @Schema(
        title = "Stopped mode",
        description = "`KEEP_CHARGING` keeps the compute resources reserved, `STOP_CHARGING` releases them so pay-as-you-go VPC instances stop being billed for vCPUs and memory. Defaults to the account's economical mode setting."
    )
    @PluginProperty(group = "advanced")
    private Property<StoppedMode> stoppedMode;

    @Override
    protected String action() {
        return "stop";
    }

    @Override
    protected TeaModel call(RunContext runContext, Client client, String regionId, List<String> ids) throws Exception {
        var request = new StopInstancesRequest()
            .setRegionId(regionId)
            .setInstanceId(ids)
            .setForceStop(runContext.render(this.forceStop).as(Boolean.class).orElse(false))
            .setStoppedMode(runContext.render(this.stoppedMode).as(StoppedMode.class).map(m -> m.value).orElse(null));
        return client.stopInstancesWithOptions(request, new RuntimeOptions()).getBody();
    }

    public enum StoppedMode {
        KEEP_CHARGING("KeepCharging"),
        STOP_CHARGING("StopCharging");

        private final String value;

        StoppedMode(String value) {
            this.value = value;
        }
    }
}
