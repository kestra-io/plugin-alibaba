package io.kestra.plugin.alibaba;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractConnection extends Task implements AlibabaConnectionInterface {
    @NotNull
    @Schema(
        title = "The AccessKey ID",
        description = "The AccessKey ID used to authenticate with Alibaba Cloud."
    )
    @PluginProperty(group = "connection")
    protected Property<String> accessKeyId;

    @NotNull
    @Schema(
        title = "The AccessKey Secret",
        description = "The AccessKey Secret used to authenticate with Alibaba Cloud."
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> accessKeySecret;

    @Schema(
        title = "Security Token",
        description = "The optional STS security token for temporary credentials."
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> securityToken;

    @NotNull
    @Schema(
        title = "Region",
        description = "The Alibaba Cloud region, e.g., 'cn-hangzhou', 'cn-shanghai'."
    )
    @PluginProperty(group = "connection")
    protected Property<String> region;

    @Schema(
        title = "Endpoint override",
        description = "Custom endpoint URL. If omitted, the default regional endpoint is derived."
    )
    @PluginProperty(group = "connection")
    protected Property<String> endpoint;

    protected String renderedAccessKeyId(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.accessKeyId).as(String.class).orElseThrow();
    }

    protected String renderedAccessKeySecret(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.accessKeySecret).as(String.class).orElseThrow();
    }

    protected String renderedSecurityToken(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.securityToken).as(String.class).orElse(null);
    }

    protected String renderedRegion(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.region).as(String.class).orElseThrow();
    }

    protected String renderedEndpoint(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.endpoint).as(String.class).orElse(null);
    }
}
