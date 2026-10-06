package io.kestra.plugin.alibaba.oss;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class UploadDownloadTest {
    private static WireMockServer wireMock;

    @Inject
    private RunContextFactory runContextFactory;

    private File sourceFile;

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
    void reset() throws Exception {
        wireMock.resetAll();
        sourceFile = File.createTempFile("oss-test", ".txt");
        Files.writeString(sourceFile.toPath(), "hello oss");
    }

    @AfterEach
    void cleanup() {
        sourceFile.delete();
    }

    @Test
    void upload() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"abc123\"")));

        var runContext = runContextFactory.of(Map.of());
        var source = runContext.storage().putFile(sourceFile);

        var output = upload(Property.ofValue(source.toString())).run(runContext);

        assertThat(output.getKey(), is("landing/data.txt"));
        assertThat(output.getEtag(), notNullValue());
        wireMock.verify(putRequestedFor(urlEqualTo("/my-bucket/landing/data.txt"))
            .withRequestBody(equalTo("hello oss")));
    }

    @Test
    void uploadRendersProperties() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/my-bucket/landing/rendered.txt"))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"abc123\"")));

        var runContext = runContextFactory.of(Map.of("name", "rendered.txt"));
        var source = runContext.storage().putFile(sourceFile);

        var task = Upload.builder()
            .id(IdUtils.create())
            .type(Upload.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofExpression("landing/{{ name }}"))
            .from(Property.ofValue(source.toString()))
            .build();

        assertThat(task.run(runContext).getKey(), is("landing/rendered.txt"));
    }

    @Test
    void uploadSendsSecurityToken() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"abc123\"")));

        var runContext = runContextFactory.of(Map.of());
        var source = runContext.storage().putFile(sourceFile);

        var task = uploadBuilder(Property.ofValue(source.toString()))
            .securityToken(Property.ofValue("sts-token"))
            .build();
        task.run(runContext);

        wireMock.verify(putRequestedFor(urlEqualTo("/my-bucket/landing/data.txt"))
            .withHeader("x-oss-security-token", equalTo("sts-token")));
    }

    @Test
    void uploadFailsWithoutBucket() {
        var runContext = runContextFactory.of(Map.of());

        var task = Upload.builder()
            .id(IdUtils.create())
            .type(Upload.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .key(Property.ofValue("landing/data.txt"))
            .from(Property.ofValue("kestra:///file.txt"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("bucket"));
    }

    @Test
    void uploadFailsWithMalformedSource() {
        var runContext = runContextFactory.of(Map.of());

        var exception = assertThrows(IllegalArgumentException.class,
            () -> upload(Property.ofValue("not a valid uri")).run(runContext));
        assertThat(exception.getMessage(), containsString("from"));
    }

    @Test
    void download() throws Exception {
        var body = "hello from oss".getBytes(StandardCharsets.UTF_8);
        wireMock.stubFor(get(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("ETag", "\"abc123\"")
                .withHeader("Content-Type", "text/plain")
                .withBody(body)));

        var runContext = runContextFactory.of(Map.of());

        var output = download("landing/data.txt").run(runContext);

        assertThat(output.getSize(), is((long) body.length));
        try (var stream = runContext.storage().getFile(output.getUri())) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8), is("hello from oss"));
        }
    }

    @Test
    void downloadFailsWhenObjectIsMissing() {
        wireMock.stubFor(get(urlEqualTo("/my-bucket/missing.txt"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>")));

        var runContext = runContextFactory.of(Map.of());

        var exception = assertThrows(IOException.class, () -> download("missing.txt").run(runContext));
        assertThat(exception.getMessage(), containsString("oss://my-bucket/missing.txt"));
    }

    @Test
    void downloadFailsWhenAccessIsDenied() {
        wireMock.stubFor(get(urlEqualTo("/my-bucket/secret.txt"))
            .willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "application/xml")
                .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code><Message>You have no right to access this object.</Message></Error>")));

        var runContext = runContextFactory.of(Map.of());

        var exception = assertThrows(IOException.class, () -> download("secret.txt").run(runContext));
        assertThat(exception.getMessage(), containsString("read access"));
    }

    @Test
    void failsWithoutRegionOrEndpoint() {
        var runContext = runContextFactory.of(Map.of());

        var task = Download.builder()
            .id(IdUtils.create())
            .type(Download.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofValue("data.txt"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("region"));
    }

    @Test
    void uploadFailsWhenAccessIsDenied() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "application/xml")
                .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code><Message>You have no right to access this object.</Message></Error>")));

        var runContext = runContextFactory.of(Map.of());
        var source = runContext.storage().putFile(sourceFile);

        var exception = assertThrows(IOException.class, () -> upload(Property.ofValue(source.toString())).run(runContext));
        assertThat(exception.getMessage(), containsString("oss://my-bucket/landing/data.txt"));
        assertThat(exception.getMessage(), containsString("write access"));
    }

    @Test
    void uploadFailsWhenBucketIsMissing() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>NoSuchBucket</Code><Message>The specified bucket does not exist.</Message></Error>")));

        var runContext = runContextFactory.of(Map.of());
        var source = runContext.storage().putFile(sourceFile);

        var exception = assertThrows(IOException.class, () -> upload(Property.ofValue(source.toString())).run(runContext));
        assertThat(exception.getMessage(), containsString("NoSuchBucket"));
    }

    @Test
    void downloadFailsWhenEndpointIsUnreachable() {
        var runContext = runContextFactory.of(Map.of());

        var task = downloadBuilder("data.txt")
            .endpointOverride(Property.ofValue("http://127.0.0.1:1"))
            .build();

        var exception = assertThrows(IOException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("endpointOverride"));
    }

    @Test
    void rejectsHttpEndpointWithoutPathStyleAccess() {
        var runContext = runContextFactory.of(Map.of());

        var task = downloadBuilder("data.txt")
            .pathStyleAccess(Property.ofValue(false))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("https"));
    }

    @Test
    void rejectsInvalidRegion() {
        var runContext = runContextFactory.of(Map.of());

        var task = Download.builder()
            .id(IdUtils.create())
            .type(Download.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .region(Property.ofValue("x.attacker.com/"))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofValue("data.txt"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("region"));
    }

    @Test
    void rejectsNonHttpEndpoint() {
        var runContext = runContextFactory.of(Map.of());

        var task = downloadBuilder("data.txt")
            .endpointOverride(Property.ofValue("file:///etc/passwd"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("endpointOverride"));
    }

    private static String endpoint() {
        return "http://127.0.0.1:" + wireMock.port();
    }

    private Upload upload(Property<String> from) {
        return uploadBuilder(from).build();
    }

    private Upload.UploadBuilder<?, ?> uploadBuilder(Property<String> from) {
        return Upload.builder()
            .id(IdUtils.create())
            .type(Upload.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofValue("landing/data.txt"))
            .from(from);
    }

    private Download download(String key) {
        return downloadBuilder(key).build();
    }

    private Download.DownloadBuilder<?, ?> downloadBuilder(String key) {
        return Download.builder()
            .id(IdUtils.create())
            .type(Download.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofValue(key));
    }
}
