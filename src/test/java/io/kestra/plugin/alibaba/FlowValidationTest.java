package io.kestra.plugin.alibaba;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.FlowSource;
import io.kestra.core.models.flows.FlowWithSource;
import io.kestra.core.models.flows.GenericFlow;
import io.kestra.core.services.FlowService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@KestraTest
class FlowValidationTest {
    @Inject
    private FlowService flowService;

    private void validate(String yaml) {
        var results = flowService.validate("main", List.of(new FlowSource("test.yaml", yaml)));
        assertNull(results.getFirst().getConstraints(), results.toString());
    }

    @Test
    void slsQueryFlowValidatesAndSavesWithDefaultLiteralAndExpressionBounds() throws Exception {
        var index = 0;
        for (var bounds : List.of("", "    offset: 0\n    lines: 1\n",
            "    offset: 10\n    lines: 100\n", "    offset: '{{ 0 }}'\n    lines: '{{ 100 }}'\n")) {
            var yaml = """
                id: sls_validation_%s
                namespace: company.team
                tasks:
                  - id: query
                    type: io.kestra.plugin.alibaba.sls.Query
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    project: ops-logs
                    logstore: app-events
                """.formatted(index++) + bounds;
            validate(yaml);
            var flow = GenericFlow.fromYaml("main", yaml);
            FlowWithSource saved;
            // The save API drops its strict-validation argument in Kestra 2.0.6.
            try {
                saved = (FlowWithSource) FlowService.class.getMethod("create", GenericFlow.class, boolean.class)
                    .invoke(flowService, flow, true);
            } catch (NoSuchMethodException e) {
                saved = (FlowWithSource) FlowService.class.getMethod("create", GenericFlow.class)
                    .invoke(flowService, flow);
            }
            assertEquals(flow.getId(), saved.getId());
        }
    }

    @Test
    void allMaxComputeAndSlsPluginExamplesValidate() {
        for (var type : List.of(io.kestra.plugin.alibaba.maxcompute.Query.class,
            io.kestra.plugin.alibaba.maxcompute.Trigger.class, io.kestra.plugin.alibaba.sls.Query.class,
            io.kestra.plugin.alibaba.sls.Push.class, io.kestra.plugin.alibaba.sls.Trigger.class)) {
            for (var example : type.getAnnotation(Plugin.class).examples()) {
                validate(String.join("\n", example.code()));
            }
        }
    }

    @Test
    void allOssPluginExamplesValidate() {
        for (var type : List.of(io.kestra.plugin.alibaba.oss.Upload.class, io.kestra.plugin.alibaba.oss.Download.class,
            io.kestra.plugin.alibaba.oss.Downloads.class, io.kestra.plugin.alibaba.oss.List.class,
            io.kestra.plugin.alibaba.oss.Delete.class, io.kestra.plugin.alibaba.oss.Copy.class,
            io.kestra.plugin.alibaba.oss.CreateBucket.class, io.kestra.plugin.alibaba.oss.DeleteBucket.class,
            io.kestra.plugin.alibaba.oss.Trigger.class)) {
            for (var example : type.getAnnotation(Plugin.class).examples()) {
                validate(String.join("\n", example.code()));
            }
        }
    }
}
