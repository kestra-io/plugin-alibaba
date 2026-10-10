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

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListDeleteCopyTest {
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
    void listFiltersByRegexpAndSkipsFolders() throws Exception {
        stubList(false, null, object("landing/", 0), object("landing/a.csv", 10), object("landing/b.txt", 20));

        var output = list().regexp(Property.ofValue(".*\\.csv")).build().run(runContextFactory.of(Map.of()));

        assertThat(output.getObjects(), hasSize(1));
        assertThat(output.getObjects().get(0).getKey(), is("landing/a.csv"));
        assertThat(output.getObjects().get(0).getSize(), is(10L));
    }

    @Test
    void listSendsPrefix() throws Exception {
        stubList(false, null, object("landing/a.csv", 10));

        list().prefix(Property.ofValue("landing/")).build().run(runContextFactory.of(Map.of()));

        wireMock.verify(getRequestedFor(urlPathMatching("/my-bucket/?"))
            .withQueryParam("prefix", equalTo("landing/")));
    }

    @Test
    void listCapsAtMaxFiles() throws Exception {
        stubList(false, null, object("a", 1), object("b", 2), object("c", 3));

        var output = list().maxFiles(Property.ofValue(2)).build().run(runContextFactory.of(Map.of()));

        assertThat(output.getObjects(), hasSize(2));
    }

    @Test
    void listFollowsPagination() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/my-bucket/?"))
            .withQueryParam("continuation-token", absent())
            .willReturn(aResponse().withStatus(200).withBody(listBody(true, "page-2", object("a", 1)))));
        wireMock.stubFor(get(urlPathMatching("/my-bucket/?"))
            .withQueryParam("continuation-token", equalTo("page-2"))
            .willReturn(aResponse().withStatus(200).withBody(listBody(false, null, object("b", 2)))));

        var output = list().build().run(runContextFactory.of(Map.of()));

        assertThat(output.getObjects(), hasSize(2));
        assertThat(output.getObjects().get(1).getKey(), is("b"));
    }

    @Test
    void listRejectsInvalidMaxFiles() {
        var task = list().maxFiles(Property.ofValue(0)).build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(exception.getMessage(), containsString("maxFiles"));
    }

    @Test
    void listFailsWithAccessDenied() {
        wireMock.stubFor(get(urlPathMatching("/my-bucket/?"))
            .willReturn(aResponse().withStatus(403).withBody(error("AccessDenied", "You have no right to access this bucket."))));

        var exception = assertThrows(IOException.class, () -> list().build().run(runContextFactory.of(Map.of())));
        assertThat(exception.getMessage(), containsString("AccessDenied"));
        assertThat(exception.getMessage(), containsString("list access"));
    }

    @Test
    void delete() throws Exception {
        wireMock.stubFor(WireMock.delete(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse().withStatus(204)));

        var output = Delete.builder()
            .id(IdUtils.create())
            .type(Delete.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofValue("landing/data.txt"))
            .build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getKey(), is("landing/data.txt"));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/my-bucket/landing/data.txt")));
    }

    @Test
    void deleteFailsWithAccessDenied() {
        wireMock.stubFor(WireMock.delete(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse().withStatus(403).withBody(error("AccessDenied", "You have no right to access this object."))));

        var task = Delete.builder()
            .id(IdUtils.create())
            .type(Delete.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofValue("landing/data.txt"))
            .build();

        var exception = assertThrows(IOException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(exception.getMessage(), containsString("write access"));
    }

    @Test
    void copy() throws Exception {
        stubCopy("/my-bucket/archive/data.txt");

        var output = copy("archive/data.txt").build().run(runContextFactory.of(Map.of()));

        assertThat(output.getBucket(), is("my-bucket"));
        assertThat(output.getKey(), is("archive/data.txt"));
        wireMock.verify(putRequestedFor(urlEqualTo("/my-bucket/archive/data.txt"))
            .withHeader("x-oss-copy-source", matching("/my-bucket/landing.*data\\.txt")));
        wireMock.verify(0, deleteRequestedFor(urlEqualTo("/my-bucket/landing/data.txt")));
    }

    @Test
    void copyWithDeleteMovesTheObject() throws Exception {
        stubCopy("/other-bucket/archive/data.txt");
        wireMock.stubFor(WireMock.delete(urlEqualTo("/my-bucket/landing/data.txt"))
            .willReturn(aResponse().withStatus(204)));

        var output = copy("archive/data.txt")
            .destinationBucket(Property.ofValue("other-bucket"))
            .delete(Property.ofValue(true))
            .build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getBucket(), is("other-bucket"));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/my-bucket/landing/data.txt")));
    }

    @Test
    void copyRejectsSameSourceAndDestination() {
        var task = copy("landing/data.txt").build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(exception.getMessage(), containsString("destination must differ"));
    }

    @Test
    void copyFailsWhenSourceIsMissing() {
        wireMock.stubFor(put(urlEqualTo("/my-bucket/archive/data.txt"))
            .willReturn(aResponse().withStatus(404).withBody(error("NoSuchKey", "The specified key does not exist."))));

        var task = copy("archive/data.txt").build();

        var exception = assertThrows(IOException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(exception.getMessage(), containsString("NoSuchKey"));
        assertThat(exception.getMessage(), containsString("oss://my-bucket/landing/data.txt"));
    }

    private static String endpoint() {
        return "http://127.0.0.1:" + wireMock.port();
    }

    private static String object(String key, long size) {
        return "<Contents><Key>" + key + "</Key><LastModified>2026-10-10T08:00:00.000Z</LastModified>"
            + "<ETag>\"etag-" + size + "\"</ETag><Size>" + size + "</Size><StorageClass>Standard</StorageClass></Contents>";
    }

    private static String listBody(boolean truncated, String nextToken, String... objects) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult><Name>my-bucket</Name><Prefix></Prefix>"
            + "<MaxKeys>1000</MaxKeys><IsTruncated>" + truncated + "</IsTruncated>"
            + (nextToken == null ? "" : "<NextContinuationToken>" + nextToken + "</NextContinuationToken>")
            + "<KeyCount>" + objects.length + "</KeyCount>" + String.join("", objects) + "</ListBucketResult>";
    }

    private static String error(String code, String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>" + message + "</Message></Error>";
    }

    private void stubList(boolean truncated, String nextToken, String... objects) {
        wireMock.stubFor(get(urlPathMatching("/my-bucket/?"))
            .willReturn(aResponse().withStatus(200).withBody(listBody(truncated, nextToken, objects))));
    }

    private void stubCopy(String path) {
        wireMock.stubFor(put(urlEqualTo(path))
            .willReturn(aResponse().withStatus(200).withBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CopyObjectResult><LastModified>2026-10-10T08:00:00.000Z</LastModified><ETag>\"copy-etag\"</ETag></CopyObjectResult>")));
    }

    private List.ListBuilder<?, ?> list() {
        return List.builder()
            .id(IdUtils.create())
            .type(List.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"));
    }

    private Copy.CopyBuilder<?, ?> copy(String destinationKey) {
        return Copy.builder()
            .id(IdUtils.create())
            .type(Copy.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .key(Property.ofValue("landing/data.txt"))
            .destinationKey(Property.ofValue(destinationKey));
    }
}
