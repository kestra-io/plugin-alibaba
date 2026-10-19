package io.kestra.plugin.alibaba.oss;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class BucketTest {
    private static WireMockServer wireMock;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void createBucket() throws Exception {
        wireMock.stubFor(put(urlPathMatching("/my-bucket/?")).willReturn(aResponse().withStatus(200)));

        var output = createBucketTask().run(runContextFactory.of(Map.of()));

        assertThat(output.getBucket(), is("my-bucket"));
        wireMock.verify(putRequestedFor(urlPathMatching("/my-bucket/?")));
    }

    @Test
    void createBucketFailsWhenTheNameIsTaken() {
        wireMock.stubFor(put(urlPathMatching("/my-bucket/?"))
            .willReturn(aResponse().withStatus(409).withBody(error("BucketAlreadyExists", "The requested bucket name is not available."))));

        var task = createBucketTask();

        var exception = assertThrows(IOException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(exception.getMessage(), containsString("BucketAlreadyExists"));
        assertThat(exception.getMessage(), containsString("not available"));
    }

    @Test
    void createBucketFailsWithoutBucket() {
        var task = CreateBucket.builder()
            .id(IdUtils.create())
            .type(CreateBucket.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(exception.getMessage(), containsString("bucket"));
    }

    @Test
    void deleteBucket() throws Exception {
        wireMock.stubFor(WireMock.delete(urlPathMatching("/my-bucket/?")).willReturn(aResponse().withStatus(204)));

        var output = deleteBucketTask().run(runContextFactory.of(Map.of()));

        assertThat(output.getBucket(), is("my-bucket"));
        wireMock.verify(deleteRequestedFor(urlPathMatching("/my-bucket/?")));
    }

    @Test
    void deleteBucketFailsWhenItIsNotEmpty() {
        wireMock.stubFor(WireMock.delete(urlPathMatching("/my-bucket/?"))
            .willReturn(aResponse().withStatus(409).withBody(error("BucketNotEmpty", "The bucket you tried to delete is not empty."))));

        var task = deleteBucketTask();

        var exception = assertThrows(IOException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(exception.getMessage(), containsString("BucketNotEmpty"));
        assertThat(exception.getMessage(), containsString("not empty"));
    }

    private static String endpoint() {
        return "http://127.0.0.1:" + wireMock.port();
    }

    private static String error(String code, String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>" + message + "</Message></Error>";
    }

    private CreateBucket createBucketTask() {
        return CreateBucket.builder()
            .id(IdUtils.create())
            .type(CreateBucket.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .build();
    }

    private DeleteBucket deleteBucketTask() {
        return DeleteBucket.builder()
            .id(IdUtils.create())
            .type(DeleteBucket.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .build();
    }
}
