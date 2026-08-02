package dev.jobradar.collector.scan.job104;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.collector.scan.ScanResult;
import dev.jobradar.collector.scan.ScraperRequestExecutor;
import dev.jobradar.common.domain.SearchQuery;
import dev.jobradar.common.source.Source;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

class Job104ListScraperTest {

    @Test
    void scansPagesUntilCurrentPageReachesLastPage() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("job104-list-page1.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("page=2")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("job104-list-page2.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        Job104ListScraper scraper = new Job104ListScraper(properties, new ObjectMapper(), builder.build(), new SimpleMeterRegistry(), new ScraperRequestExecutor(new SimpleMeterRegistry()));
        SearchQuery query = new SearchQuery(1, Source.JOB104, "6001001000", List.of("2007001016"), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> false);

        assertThat(result.pagesScanned()).isEqualTo(2);
        assertThat(result.discovered()).hasSize(3);
        // jobNo（upsert 用的來源職缺 id）跟 slug（detail API 用）是兩套不同的識別碼系統
        // （見 source-api-notes.md），fixture 故意用不同格式的值驗證兩者不會被搞混
        assertThat(result.discovered().get(0).sourceJobId()).isEqualTo("13500123");
        assertThat(result.discovered().get(0).url()).isEqualTo("https://www.104.com.tw/job/72f7l");
        assertThat(result.discovered().get(0).detailUrl()).isEqualTo("72f7l");
        assertThat(result.discovered().get(0).needsDetail()).isTrue();
        assertThat(result.discovered().get(0).payload().path("jobName").asText()).isEqualTo("QA Engineer");
        assertThat(result.discovered().get(2).sourceJobId()).isEqualTo("13500125");
        assertThat(result.reachedEnd()).isTrue();

        server.verify();
    }

    /**
     * 分頁 API 常見的隱性 bug：頁碼超出真實範圍後悄悄重複回傳同一批內容，
     * metadata.pagination 完全不會反映這種情況——比照 Yourator/CakeResume 同一套
     * 安全網（見 Job104ListScraper 類別註解）。
     */
    @Test
    void stopsWhenConsecutivePagesReturnIdenticalJobSet() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess(fixture("job104-list-page1.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("page=2")))
                .andRespond(withSuccess(fixture("job104-list-page1-duplicate.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Job104ListScraper scraper = new Job104ListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.JOB104, "6001001000", List.of("2007001016"), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> false);

        assertThat(result.pagesScanned()).isEqualTo(1);
        assertThat(result.discovered()).hasSize(2);
        assertThat(meterRegistry.get("jobradar.scrape.anomaly")
                        .tag("source", "104")
                        .tag("reason", "duplicate_page")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    @Test
    void rateLimitRetryIsCounted() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess(fixture("job104-list-page2.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Job104ListScraper scraper = new Job104ListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.JOB104, "6001001000", List.of("2007001016"), 120, true, null);

        scraper.scan(query, false, 1, ids -> false);

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

        server.expect(requestTo(containsString("page=1")))
                .andRespond(request -> {
                    throw new SocketTimeoutException("simulated read timeout");
                });
        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess(fixture("job104-list-page2.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Job104ListScraper scraper = new Job104ListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.JOB104, "6001001000", List.of("2007001016"), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> false);

        assertThat(result.discovered()).hasSize(1);
        assertThat(meterRegistry.get("jobradar.scrape.retry")
                        .tag("source", "104")
                        .tag("reason", "io_timeout")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    /**
     * 淺掃早停（見 architecture.md D6）：即使 currentPage < lastPage，整頁都已知就
     * 提早停止，不會再打第 2 頁——只設一個 page=1 的 expectation，scraper 若誤打
     * page=2 會直接讓 MockRestServiceServer 拋出未預期請求的例外。
     */
    @Test
    void stopsEarlyWhenPageIsFullyKnownAndNotInDeepMode() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess(fixture("job104-list-page1.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        Job104ListScraper scraper = new Job104ListScraper(properties, new ObjectMapper(), builder.build(), new SimpleMeterRegistry(), new ScraperRequestExecutor(new SimpleMeterRegistry()));
        SearchQuery query = new SearchQuery(1, Source.JOB104, "6001001000", List.of("2007001016"), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> true);

        assertThat(result.pagesScanned()).isEqualTo(1);
        assertThat(result.discovered()).hasSize(2);
        assertThat(result.reachedEnd()).isTrue();
        server.verify();
    }

    /**
     * 深掃接續（見 architecture.md D6）：startPage 非 1 時直接從該頁開始打，不是每次
     * 都重新從第 1 頁翻——只設一個 page=2 的 expectation，驗證第一次請求就是打 page=2。
     */
    @Test
    void deepModeStartsFromGivenStartPage() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=2")))
                .andRespond(withSuccess(fixture("job104-list-page2.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        Job104ListScraper scraper = new Job104ListScraper(properties, new ObjectMapper(), builder.build(), new SimpleMeterRegistry(), new ScraperRequestExecutor(new SimpleMeterRegistry()));
        SearchQuery query = new SearchQuery(1, Source.JOB104, "6001001000", List.of("2007001016"), 120, true, null);

        ScanResult result = scraper.scan(query, true, 2, ids -> false);

        assertThat(result.pagesScanned()).isEqualTo(1);
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

        server.expect(requestTo(containsString("page=1")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Job104ListScraper scraper = new Job104ListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.JOB104, "6001001000", List.of("2007001016"), 120, true, null);

        assertThatThrownBy(() -> scraper.scan(query, false, 1, ids -> false))
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

        server.expect(requestTo(containsString("page=1")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Job104ListScraper scraper = new Job104ListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.JOB104, "6001001000", List.of("2007001016"), 120, true, null);

        assertThatThrownBy(() -> scraper.scan(query, false, 1, ids -> false))
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
