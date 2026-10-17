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

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class DownloadsTest {
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
    void downloadsWithoutTouchingTheBucketByDefault() throws Exception {
        stubList("landing/a.csv", "landing/b.csv");
        stubObject("landing/a.csv");
        stubObject("landing/b.csv");

        var output = downloads().build().run(runContextFactory.of(Map.of()));

        assertThat(output.getObjects(), hasSize(2));
        assertThat(output.getObjects().get(0).getUri(), notNullValue());
        assertThat(output.getObjects().get(0).getActionFailed(), is(false));
        wireMock.verify(0, deleteRequestedFor(urlPathMatching("/my-bucket/.*")));
        wireMock.verify(0, putRequestedFor(urlPathMatching("/my-bucket/.*")));
    }

    @Test
    void deletesObjectsAfterDownloadingThem() throws Exception {
        stubList("landing/a.csv", "landing/b.csv");
        stubObject("landing/a.csv");
        stubObject("landing/b.csv");
        stubDelete("landing/a.csv");
        stubDelete("landing/b.csv");

        var output = downloads().action(Property.ofValue(Downloads.Action.DELETE)).build().run(runContextFactory.of(Map.of()));

        assertThat(output.getObjects(), hasSize(2));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/my-bucket/landing/a.csv")));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/my-bucket/landing/b.csv")));
    }

    @Test
    void movesObjectsAfterDownloadingThem() throws Exception {
        stubList("landing/a.csv");
        stubObject("landing/a.csv");
        stubHead("landing/a.csv");
        wireMock.stubFor(put(urlEqualTo("/my-bucket/archive/landing/a.csv"))
            .willReturn(aResponse().withStatus(200).withBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CopyObjectResult><LastModified>2026-10-10T08:00:00.000Z</LastModified><ETag>\"copy-etag\"</ETag></CopyObjectResult>")));
        stubDelete("landing/a.csv");

        var output = downloads()
            .action(Property.ofValue(Downloads.Action.MOVE))
            .moveToPrefix(Property.ofValue("archive/"))
            .build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getObjects(), hasSize(1));
        wireMock.verify(putRequestedFor(urlEqualTo("/my-bucket/archive/landing/a.csv"))
            .withHeader("x-oss-copy-source", matching("/my-bucket/landing.*a\\.csv")));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/my-bucket/landing/a.csv")));
    }

    @Test
    void returnsNoObjectsWhenNothingMatches() throws Exception {
        stubList();

        var output = downloads().build().run(runContextFactory.of(Map.of()));

        assertThat(output.getObjects(), hasSize(0));
    }

    private static String endpoint() {
        return "http://127.0.0.1:" + wireMock.port();
    }

    private void stubList(String... keys) {
        var contents = new StringBuilder();
        for (var key : keys) {
            contents.append("<Contents><Key>").append(key).append("</Key><LastModified>2026-10-10T08:00:00.000Z</LastModified><ETag>\"etag-1\"</ETag><Size>3</Size><StorageClass>Standard</StorageClass></Contents>");
        }
        wireMock.stubFor(get(urlPathMatching("/my-bucket/?"))
            .willReturn(aResponse().withStatus(200).withBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult><Name>my-bucket</Name><Prefix></Prefix><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated><KeyCount>" + keys.length + "</KeyCount>" + contents + "</ListBucketResult>")));
    }

    private void stubObject(String key) {
        wireMock.stubFor(get(urlEqualTo("/my-bucket/" + key))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"etag-1\"").withBody("a,b")));
    }

    private void stubHead(String key) {
        wireMock.stubFor(head(urlEqualTo("/my-bucket/" + key))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Length", "3")
                .withHeader("ETag", "\"etag-1\"")
                .withHeader("Last-Modified", "Sat, 10 Oct 2026 08:00:00 GMT")));
    }

    private void stubDelete(String key) {
        wireMock.stubFor(WireMock.delete(urlEqualTo("/my-bucket/" + key)).willReturn(aResponse().withStatus(204)));
    }

    private Downloads.DownloadsBuilder<?, ?> downloads() {
        return Downloads.builder()
            .id(IdUtils.create())
            .type(Downloads.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue(endpoint()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .prefix(Property.ofValue("landing/"));
    }
}
