package io.kestra.plugin.alibaba.ecs;

import com.aliyun.ecs20140526.Client;
import com.aliyun.ecs20140526.models.StartInstancesRequest;
import com.aliyun.tea.TeaModel;
import com.aliyun.teautil.models.RuntimeOptions;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "Start ECS instances",
    description = "Starts one or more stopped ECS instances. The task returns once Alibaba Cloud accepts the request, while instances are still `Starting`."
)
@Plugin(
    examples = {
        @Example(
            title = "Start two instances",
            full = true,
            code = """
                id: ecs_start
                namespace: company.team

                tasks:
                  - id: start
                    type: io.kestra.plugin.alibaba.ecs.Start
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    instanceIds:
                      - i-bp1example0000001
                      - i-bp1example0000002
                """
        )
    }
)
public class Start extends AbstractInstanceAction {
    @Override
    protected String action() {
        return "start";
    }

    @Override
    protected TeaModel call(RunContext runContext, Client client, String regionId, List<String> ids) throws Exception {
        var request = new StartInstancesRequest()
            .setRegionId(regionId)
            .setInstanceId(ids);
        return client.startInstancesWithOptions(request, new RuntimeOptions()).getBody();
    }
}
