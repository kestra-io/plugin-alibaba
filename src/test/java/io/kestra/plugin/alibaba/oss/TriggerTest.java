package io.kestra.plugin.alibaba.oss;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@KestraTest
class TriggerTest {
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
    void downloadsThenDeletesMatchingObjects() throws Exception {
        stubListWithOneObject();
        stubObject();
        wireMock.stubFor(WireMock.delete(urlEqualTo("/my-bucket/landing/a.csv")).willReturn(aResponse().withStatus(204)));

        var trigger = trigger(Trigger.Action.DELETE).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        var execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));
        var objects = (List<?>) execution.get().getTrigger().getVariables().get("objects");
        assertThat(objects, hasSize(1));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/my-bucket/landing/a.csv")));
    }

    @Test
    void movesMatchingObjects() throws Exception {
        stubListWithOneObject();
        stubObject();
        wireMock.stubFor(put(urlEqualTo("/my-bucket/archive/landing/a.csv"))
            .willReturn(aResponse().withStatus(200).withBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CopyObjectResult><LastModified>2026-10-10T08:00:00.000Z</LastModified><ETag>\"copy-etag\"</ETag></CopyObjectResult>")));
        wireMock.stubFor(WireMock.delete(urlEqualTo("/my-bucket/landing/a.csv")).willReturn(aResponse().withStatus(204)));

        var trigger = trigger(Trigger.Action.MOVE).moveToPrefix(Property.ofValue("archive/")).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        var execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));
        wireMock.verify(putRequestedFor(urlEqualTo("/my-bucket/archive/landing/a.csv"))
            .withHeader("x-oss-copy-source", matching("/my-bucket/landing.*a\\.csv")));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/my-bucket/landing/a.csv")));
    }

    @Test
    void doesNotTriggerWhenNothingMatches() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/my-bucket/?"))
            .willReturn(aResponse().withStatus(200).withBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult><Name>my-bucket</Name><Prefix></Prefix><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated><KeyCount>0</KeyCount></ListBucketResult>")));

        var trigger = trigger(Trigger.Action.DELETE).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
    }

    private void stubListWithOneObject() {
        wireMock.stubFor(get(urlPathMatching("/my-bucket/?"))
            .willReturn(aResponse().withStatus(200).withBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult><Name>my-bucket</Name><Prefix></Prefix><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated><KeyCount>1</KeyCount>"
                    + "<Contents><Key>landing/a.csv</Key><LastModified>2026-10-10T08:00:00.000Z</LastModified><ETag>\"etag-1\"</ETag><Size>3</Size><StorageClass>Standard</StorageClass></Contents></ListBucketResult>")));
    }

    private void stubObject() {
        wireMock.stubFor(get(urlEqualTo("/my-bucket/landing/a.csv"))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"etag-1\"").withBody("a,b")));
    }

    private Trigger.TriggerBuilder<?, ?> trigger(Trigger.Action action) {
        return Trigger.builder()
            .id("watch")
            .type(Trigger.class.getName())
            .accessKeyId(Property.ofValue("test-id"))
            .accessKeySecret(Property.ofValue("test-secret"))
            .endpointOverride(Property.ofValue("http://127.0.0.1:" + wireMock.port()))
            .pathStyleAccess(Property.ofValue(true))
            .bucket(Property.ofValue("my-bucket"))
            .prefix(Property.ofValue("landing/"))
            .action(Property.ofValue(action));
    }
}
