package io.kestra.plugin.alibaba.fc;

import com.aliyun.fc20230330.Client;
import com.aliyun.fc20230330.models.InvokeFunctionHeaders;
import com.aliyun.fc20230330.models.InvokeFunctionRequest;
import com.aliyun.fc20230330.models.InvokeFunctionResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.alibaba.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Invoke a Function Compute function",
    description = "Invokes an Alibaba Cloud Function Compute 3.0 function synchronously or asynchronously and outputs the response status and body. Fails on a non-2xx response."
)
@Plugin(
    examples = {
        @Example(
            title = "Invoke a function synchronously and log its response",
            full = true,
            code = """
                id: fc_invoke
                namespace: company.team

                tasks:
                  - id: invoke
                    type: io.kestra.plugin.alibaba.fc.Invoke
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    accountId: "{{ secret('ALIBABA_ACCOUNT_ID') }}"
                    functionName: resize-image
                    payload:
                      key: landing/photo.png

                  - id: log_response
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.invoke.body }}"
                """
        ),
        @Example(
            title = "Invoke a function asynchronously",
            full = true,
            code = """
                id: fc_invoke_async
                namespace: company.team

                tasks:
                  - id: invoke
                    type: io.kestra.plugin.alibaba.fc.Invoke
                    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
                    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
                    region: cn-hangzhou
                    accountId: "{{ secret('ALIBABA_ACCOUNT_ID') }}"
                    functionName: send-report
                    invocationType: ASYNC
                    payload:
                      reportId: "{{ execution.id }}"
                """
        )
    }
)
public class Invoke extends AbstractConnection implements RunnableTask<Invoke.Output> {
    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[0-9]+$");

    @Schema(
        title = "Account ID",
        description = "The Alibaba Cloud account ID, used to derive the default endpoint `<accountId>.<region>.fc.aliyuncs.com`. Not needed when `endpointOverride` is set."
    )
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(
        title = "Function name",
        description = "The name of the function to invoke."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> functionName;

    @Schema(
        title = "Qualifier",
        description = "The version or alias of the function to invoke. Defaults to `LATEST`."
    )
    @PluginProperty(group = "main")
    private Property<String> qualifier;

    @Schema(
        title = "Payload",
        description = "The event sent to the function, serialized as JSON."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> payload;

    @Schema(
        title = "Invocation type",
        description = "`SYNC` waits for the function result, `ASYNC` queues the event and returns immediately with an empty body."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<InvocationType> invocationType = Property.ofValue(InvocationType.SYNC);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var function = required(runContext, this.functionName, "functionName");
        var type = runContext.render(this.invocationType).as(InvocationType.class).orElse(InvocationType.SYNC);
        var event = runContext.render(this.payload).asMap(String.class, Object.class);
        var body = event.isEmpty() ? new byte[0] : JacksonMapper.ofJson().writeValueAsBytes(event);

        var request = new InvokeFunctionRequest()
            .setBody(new ByteArrayInputStream(body))
            .setQualifier(runContext.render(this.qualifier).as(String.class).orElse(null));
        var headers = new InvokeFunctionHeaders()
            .setXFcInvocationType(type == InvocationType.SYNC ? "Sync" : "Async");

        InvokeFunctionResponse response;
        try {
            response = client(runContext).invokeFunctionWithOptions(function, request, headers, new RuntimeOptions());
        } catch (TeaException e) {
            throw new IllegalStateException(failure(function, e.getStatusCode(), e.getCode() + ": " + e.getMessage()), e);
        }

        int status = response.getStatusCode();
        var responseBody = read(response.getBody());
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(failure(function, status, responseBody));
        }

        var responseHeaders = response.getHeaders() == null ? Map.<String, String>of() : response.getHeaders();
        var requestId = responseHeaders.get("x-fc-request-id");
        runContext.logger().debug("Invoked function '{}' ({}), status {}, request ID {}", function, type, status, requestId);

        return Output.builder()
            .statusCode(status)
            .body(responseBody)
            .requestId(requestId)
            .build();
    }

    private static String failure(String function, Integer status, String detail) {
        return "Function Compute invocation of '" + function + "' failed with status " + status + " (" + detail + "): " +
            "check that the function exists in this region and account, and that the credentials have the fc:InvokeFunction permission";
    }

    private static String required(RunContext runContext, Property<String> property, String name) throws IllegalVariableEvaluationException {
        return runContext.render(property).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`" + name + "` is required"));
    }

    private Client client(RunContext runContext) throws Exception {
        var config = new Config()
            .setAccessKeyId(required(runContext, this.accessKeyId, "accessKeyId"))
            .setAccessKeySecret(required(runContext, this.accessKeySecret, "accessKeySecret"))
            .setSecurityToken(runContext.render(this.securityToken).as(String.class).orElse(null));

        var override = runContext.render(this.endpointOverride).as(String.class).orElse(null);
        if (override != null) {
            var uri = endpointUri(override);
            if (uri.getScheme().equals("http")) {
                runContext.logger().warn("`endpointOverride` uses plain http, credentials and signed requests are sent unencrypted to {}", uri.getAuthority());
            }
            config.setProtocol(uri.getScheme()).setEndpoint(uri.getAuthority());
        } else {
            var regionId = runContext.render(this.region).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("Either `region` or `endpointOverride` must be set"));
            if (!REGION_PATTERN.matcher(regionId).matches()) {
                throw new IllegalArgumentException("`region` must only contain lowercase letters, digits and dashes, got: " + regionId);
            }
            var account = required(runContext, this.accountId, "accountId");
            if (!ACCOUNT_PATTERN.matcher(account).matches()) {
                throw new IllegalArgumentException("`accountId` must only contain digits, got: " + account);
            }
            config.setRegionId(regionId).setEndpoint(account + "." + regionId + ".fc.aliyuncs.com");
        }

        return new Client(config);
    }

    private static URI endpointUri(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("`endpointOverride` is not a valid URL: " + endpoint, e);
        }

        var scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("`endpointOverride` must be an http or https URL, got: " + endpoint);
        }
        return uri;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return null;
        }
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public enum InvocationType {
        SYNC,
        ASYNC
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "HTTP status code returned by Function Compute")
        private final Integer statusCode;

        @Schema(title = "Response body returned by the function, empty for asynchronous invocations")
        private final String body;

        @Schema(title = "Function Compute request ID")
        private final String requestId;
    }
}
