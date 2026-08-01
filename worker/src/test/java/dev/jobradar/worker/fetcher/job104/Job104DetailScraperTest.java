package dev.jobradar.worker.fetcher.job104;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

class Job104DetailScraperTest {

    @Test
    void fetchesDetailBySlugAndReturnsParsedJson() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/jobs/72f7l")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("job104-detail.json"), MediaType.APPLICATION_JSON));

        Job104DetailScraper scraper = new Job104DetailScraper(builder, new SimpleMeterRegistry(), "test-agent");

        JsonNode result = scraper.fetch("72f7l", "72f7l");

        assertThat(result.path("data").path("header").path("jobName").asText()).isEqualTo("QA Engineer");
        server.verify();
    }

    @Test
    void rateLimitRetryIsCounted() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/jobs/72f7l")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(containsString("/api/jobs/72f7l")))
                .andRespond(withSuccess(fixture("job104-detail.json"), MediaType.APPLICATION_JSON));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Job104DetailScraper scraper = new Job104DetailScraper(builder, meterRegistry, "test-agent");

        scraper.fetch("72f7l", "72f7l");

        assertThat(meterRegistry.get("jobradar.scrape.retry")
                        .tag("source", "104")
                        .tag("reason", "rate_limited")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    @Test
    void ioTimeoutRetryIsCounted() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/jobs/72f7l")))
                .andRespond(request -> {
                    throw new SocketTimeoutException("simulated read timeout");
                });
        server.expect(requestTo(containsString("/api/jobs/72f7l")))
                .andRespond(withSuccess(fixture("job104-detail.json"), MediaType.APPLICATION_JSON));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Job104DetailScraper scraper = new Job104DetailScraper(builder, meterRegistry, "test-agent");

        JsonNode result = scraper.fetch("72f7l", "72f7l");

        assertThat(result.path("data").path("header").path("jobName").asText()).isEqualTo("QA Engineer");
        assertThat(meterRegistry.get("jobradar.scrape.retry")
                        .tag("source", "104")
                        .tag("reason", "io_timeout")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    /**
     * 疑似 Cloudflare 風控相關（見 architecture.md D19）：403/503 不重試，只設一個
     * expectation，如果誤重試會讓 MockRestServiceServer 拋出未預期請求的例外。
     */
    @Test
    void forbiddenResponseIsNotRetriedAndMarksBlocked() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/jobs/72f7l")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Job104DetailScraper scraper = new Job104DetailScraper(builder, meterRegistry, "test-agent");

        assertThatThrownBy(() -> scraper.fetch("72f7l", "72f7l"))
                .isInstanceOf(dev.jobradar.common.source.SourceBlockedException.class);

        assertThat(meterRegistry.get("jobradar.scrape.anomaly")
                        .tag("source", "104")
                        .tag("reason", "blocked")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    @Test
    void serviceUnavailableResponseIsNotRetriedAndMarksBlocked() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/jobs/72f7l")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Job104DetailScraper scraper = new Job104DetailScraper(builder, meterRegistry, "test-agent");

        assertThatThrownBy(() -> scraper.fetch("72f7l", "72f7l"))
                .isInstanceOf(dev.jobradar.common.source.SourceBlockedException.class);

        assertThat(meterRegistry.get("jobradar.scrape.anomaly")
                        .tag("source", "104")
                        .tag("reason", "blocked")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
