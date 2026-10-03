package io.kestra.plugin.alibaba.oss;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class UploadDownloadTest {
    private static WireMockServer wireMock;

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storageInterface;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @Test
    void upload() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"abc123\"")));

        RunContext runContext = runContextFactory.of(Map.of());
        URI source = runContext.storage().putFile(writeTempFile("hello oss"));

        Upload task = Upload.builder()
            .id("upload")
            .type(Upload.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue("http://127.0.0.1:" + wireMock.port()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofValue("landing/data.txt"))
            .from(Property.ofValue(source.toString()))
            .build();

        Upload.Output output = task.run(runContext);

        assertThat(output.getKey(), is("landing/data.txt"));
        assertThat(output.getEtag(), notNullValue());
        wireMock.verify(putRequestedFor(urlEqualTo("/my-bucket/landing/data.txt")));
    }

    @Test
    void download() throws Exception {
        byte[] body = "hello from oss".getBytes(StandardCharsets.UTF_8);
        wireMock.stubFor(get(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("ETag", "\"abc123\"")
                .withHeader("Content-Type", "text/plain")
                .withBody(body)));

        RunContext runContext = runContextFactory.of(Map.of());

        Download task = Download.builder()
            .id("download")
            .type(Download.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue("http://127.0.0.1:" + wireMock.port()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofValue("landing/data.txt"))
            .build();

        Download.Output output = task.run(runContext);

        assertThat(output.getSize(), is((long) body.length));
        try (InputStream stream = runContext.storage().getFile(output.getUri())) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8), is("hello from oss"));
        }
    }

    private static File writeTempFile(String content) throws Exception {
        File file = File.createTempFile("oss-test", ".txt");
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
