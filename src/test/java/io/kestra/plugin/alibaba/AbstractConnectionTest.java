package io.kestra.plugin.alibaba;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class AbstractConnectionTest {
    @Inject
    private RunContextFactory runContextFactory;

    @SuperBuilder
    @Getter
    @NoArgsConstructor
    public static class TestConnectionTask extends AbstractConnection {
    }

    @Test
    void shouldRenderConnectionProperties() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of(
            "ak", "my-access-key",
            "sk", "my-secret-key",
            "token", "my-sts-token",
            "region", "cn-beijing"
        ));

        TestConnectionTask task = TestConnectionTask.builder()
            .accessKeyId(Property.ofExpression("{{ ak }}"))
            .accessKeySecret(Property.ofExpression("{{ sk }}"))
            .securityToken(Property.ofExpression("{{ token }}"))
            .region(Property.ofExpression("{{ region }}"))
            .endpoint(Property.ofValue("https://custom.endpoint.com"))
            .build();

        assertThat(task.renderedAccessKeyId(runContext), is("my-access-key"));
        assertThat(task.renderedAccessKeySecret(runContext), is("my-secret-key"));
        assertThat(task.renderedSecurityToken(runContext), is("my-sts-token"));
        assertThat(task.renderedRegion(runContext), is("cn-beijing"));
        assertThat(task.renderedEndpoint(runContext), is("https://custom.endpoint.com"));
    }

    @Test
    void shouldExcludeSecretsFromToString() {
        TestConnectionTask task = TestConnectionTask.builder()
            .accessKeyId(Property.ofValue("visible-key"))
            .accessKeySecret(Property.ofValue("super-secret-key"))
            .securityToken(Property.ofValue("super-secret-token"))
            .region(Property.ofValue("cn-hangzhou"))
            .build();

        String toString = task.toString();
        assertThat(toString, not(containsString("super-secret-key")));
        assertThat(toString, not(containsString("super-secret-token")));
    }
}
