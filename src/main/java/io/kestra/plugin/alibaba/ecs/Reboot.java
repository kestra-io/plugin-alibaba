package io.kestra.plugin.alibaba.ecs;

import com.aliyun.ecs20140526.Client;
import com.aliyun.ecs20140526.models.RebootInstancesRequest;
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
    title = "Reboot ECS instances",
    description = "Reboots one or more running ECS instances. The task returns once Alibaba Cloud accepts the request."
)
@Plugin(
    examples = {
        @Example(
            title = "Reboot an instance",
            full = true,
            code = """
                id: ecs_reboot
                namespace: company.team

                tasks:
                  - id: reboot
                    type: io.kestra.plugin.alibaba.ecs.Reboot
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    instanceIds:
                      - i-bp1example0000001
                """
        )
    }
)
public class Reboot extends AbstractInstanceAction {
    @Schema(
        title = "Force reboot",
        description = "Reboot the instances immediately, like a power cut. Data not written to disk may be lost."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Boolean> forceReboot = Property.ofValue(false);

    @Override
    protected String action() {
        return "reboot";
    }

    @Override
    protected TeaModel call(RunContext runContext, Client client, String regionId, List<String> ids) throws Exception {
        var request = new RebootInstancesRequest()
            .setRegionId(regionId)
            .setInstanceId(ids)
            .setForceReboot(runContext.render(this.forceReboot).as(Boolean.class).orElse(false));
        return client.rebootInstancesWithOptions(request, new RuntimeOptions()).getBody();
    }
}
