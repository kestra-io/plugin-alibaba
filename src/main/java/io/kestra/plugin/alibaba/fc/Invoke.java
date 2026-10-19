package io.kestra.plugin.alibaba.fc;

import com.aliyun.fc20230330.Client;
import com.aliyun.fc20230330.models.InvokeFunctionHeaders;
import com.aliyun.fc20230330.models.InvokeFunctionRequest;
import com.aliyun.fc20230330.models.InvokeFunctionResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.tea.TeaUnretryableException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
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
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Invoke a Function Compute function",
    description = "Invokes an Alibaba Cloud Function Compute 3.0 function synchronously or asynchronously and outputs the response status and body. Fails on a non-2xx response, and when the function itself throws (status 200 with an `X-Fc-Error-Type` header). A call waits for the response up to 90% of the task `timeout`, so the plugin error is shown instead of the generic Kestra timeout, or 10 minutes when it is not set."
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
    private static final String DEFAULT_QUALIFIER = "LATEST";
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // well below Kestra 2.0's default 1 MiB queue message limit, which the whole task result must fit in
    private static final int MAX_BODY_OUTPUT_BYTES = 512 * 1024;
    private static final int MAX_ERROR_EXCERPT_BYTES = 1024;

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
        description = "The event sent to the function. A map, list, number or boolean is serialized as JSON, and a string is sent as is, so a rendered JSON text or plain text event can be passed directly.",
        anyOf = {Map.class, java.util.List.class, String.class, Number.class, Boolean.class}
    )
    @PluginProperty(group = "main")
    private Property<Object> payload;

    @Schema(
        title = "Invocation type",
        description = "`SYNC` waits for the function result, `ASYNC` queues the event and returns immediately with an empty body."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<InvocationType> invocationType = Property.ofValue(InvocationType.SYNC);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rFunctionName = required(runContext, this.functionName, "functionName");
        var rQualifier = runContext.render(this.qualifier).as(String.class).orElse(DEFAULT_QUALIFIER);
        var rInvocationType = runContext.render(this.invocationType).as(InvocationType.class).orElse(InvocationType.SYNC);
        var rPayload = runContext.render(this.payload).as(Object.class).orElse(null);
        // 90% of the task `timeout`, so the SDK fails first with the actionable message instead of Kestra's generic timeout
        var rReadTimeout = runContext.render(this.getTimeout()).as(Duration.class)
            .map(timeout -> timeout.multipliedBy(9).dividedBy(10))
            .orElse(DEFAULT_READ_TIMEOUT);

        var response = invoke(runContext, rFunctionName, rQualifier, rInvocationType, requestBody(rPayload), rReadTimeout);
        var responseFile = storeResponse(runContext, response);
        var requestId = header(response, "x-fc-request-id");
        checkResponse(rFunctionName, response, requestId, responseFile);

        runContext.logger().debug("Invoked function '{}' ({}), status {}, request ID {}", rFunctionName, rInvocationType, response.getStatusCode(), requestId);

        var body = Files.size(responseFile) <= MAX_BODY_OUTPUT_BYTES ? textOrNull(responseFile) : null;
        return Output.builder()
            .statusCode(response.getStatusCode())
            .uri(runContext.storage().putFile(responseFile.toFile()))
            .body(body)
            .requestId(requestId)
            .build();
    }

    private static byte[] requestBody(Object rPayload) throws IOException {
        return switch (rPayload) {
            case null -> new byte[0];
            case String text -> text.getBytes(StandardCharsets.UTF_8);
            default -> JacksonMapper.ofJson().writeValueAsBytes(rPayload);
        };
    }

    private InvokeFunctionResponse invoke(RunContext runContext, String rFunctionName, String rQualifier, InvocationType rInvocationType, byte[] body, Duration rReadTimeout) throws Exception {
        var request = new InvokeFunctionRequest()
            .setBody(new ByteArrayInputStream(body))
            .setQualifier(rQualifier);
        var headers = new InvokeFunctionHeaders()
            .setXFcInvocationType(rInvocationType == InvocationType.SYNC ? "Sync" : "Async");
        var options = new RuntimeOptions()
            .setConnectTimeout((int) CONNECT_TIMEOUT.toMillis())
            .setReadTimeout((int) Math.min(rReadTimeout.toMillis(), Integer.MAX_VALUE));

        try {
            return client(runContext).invokeFunctionWithOptions(rFunctionName, request, headers, options);
        } catch (TeaException e) {
            throw new IllegalStateException(failure(rFunctionName, e.getStatusCode(), e.getCode() + ": " + e.getMessage()), e);
        } catch (TeaUnretryableException e) {
            throw new IllegalStateException(
                "Function Compute invocation of '" + rFunctionName + "' got no response (" + e.getMessage() + "): check network access to the endpoint. " +
                    "If the function runs longer than " + rReadTimeout + ", raise the task `timeout` or use `invocationType: ASYNC`",
                e
            );
        }
    }

    private static Path storeResponse(RunContext runContext, InvokeFunctionResponse response) throws IOException {
        var responseFile = runContext.workingDir().createTempFile();
        if (response.getBody() != null) {
            try (var stream = response.getBody()) {
                Files.copy(stream, responseFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return responseFile;
    }

    private static void checkResponse(String rFunctionName, InvokeFunctionResponse response, String requestId, Path responseFile) throws IOException {
        var status = response.getStatusCode();
        if (status == null || status < 200 || status >= 300) {
            throw new IllegalStateException(failure(rFunctionName, status, excerpt(responseFile)));
        }

        var errorType = header(response, "x-fc-error-type");
        if (errorType != null) {
            throw new IllegalStateException(
                "Function '" + rFunctionName + "' failed with " + errorType + " (" + excerpt(responseFile) + "): check the function logs for request ID " + requestId
            );
        }
    }

    private static String textOrNull(Path file) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String header(InvokeFunctionResponse response, String name) {
        if (response.getHeaders() == null) {
            return null;
        }
        return response.getHeaders().entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    private static String excerpt(Path file) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            var bytes = stream.readNBytes(MAX_ERROR_EXCERPT_BYTES + 1);
            var text = new String(bytes, 0, Math.min(bytes.length, MAX_ERROR_EXCERPT_BYTES), StandardCharsets.UTF_8);
            return bytes.length > MAX_ERROR_EXCERPT_BYTES ? text + "..." : text;
        }
    }

    private static String failure(String rFunctionName, Integer status, String detail) {
        var prefix = "Function Compute invocation of '" + rFunctionName + "' failed" + (status == null ? "" : " with status " + status);
        return prefix + " (" + detail + "): " + hint(status);
    }

    private static String hint(Integer status) {
        if (status == null) {
            return "check network access to the Function Compute endpoint";
        }
        return switch (status) {
            case 400 -> "check that the payload and qualifier are valid for this function";
            case 401, 403 -> "check the credentials and that they have the fc:InvokeFunction permission";
            case 404 -> "check that the function and qualifier exist in this region and account";
            case 429 -> "the function is throttled, retry later or raise its concurrency limit";
            default -> status >= 500 ? "Function Compute returned a service error, retry later" : "check the request against the Function Compute API reference";
        };
    }

    private Client client(RunContext runContext) throws Exception {
        var config = new Config()
            .setAccessKeyId(renderedAccessKeyId(runContext))
            .setAccessKeySecret(renderedAccessKeySecret(runContext))
            .setSecurityToken(renderedSecurityToken(runContext));

        var rEndpointOverride = rEndpointOverride(runContext, this);
        if (rEndpointOverride != null) {
            config.setProtocol(rEndpointOverride.getScheme()).setEndpoint(rEndpointOverride.getAuthority());
        } else {
            var rRegion = rRegion(runContext, this);
            var rAccountId = rAccountId(runContext, this.accountId, "accountId");
            config.setRegionId(rRegion).setEndpoint(rAccountId + "." + rRegion + ".fc.aliyuncs.com");
        }
        return new Client(config);
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

        @Schema(
            title = "Response body as text",
            description = "Only set when the response is at most 512 KB, use `uri` for larger responses. Empty for asynchronous invocations."
        )
        private final String body;

        @Schema(title = "URI of the response body in Kestra internal storage")
        private final URI uri;

        @Schema(title = "Function Compute request ID")
        private final String requestId;
    }
}
