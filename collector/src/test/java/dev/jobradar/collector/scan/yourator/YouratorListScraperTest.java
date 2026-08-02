package dev.jobradar.collector.scan.yourator;

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

class YouratorListScraperTest {

    @Test
    void scansPagesUntilHasMoreIsFalseAndCollectsRawPayloads() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("yourator-list-page1.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("page=2")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("yourator-list-page2.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder.build(), new SimpleMeterRegistry(), new ScraperRequestExecutor(new SimpleMeterRegistry()));
        SearchQuery query = new SearchQuery(1, Source.YOURATOR, "TPE", List.of(), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> false);

        assertThat(result.pagesScanned()).isEqualTo(2);
        assertThat(result.discovered()).hasSize(3);
        assertThat(result.discovered().get(0).sourceJobId()).isEqualTo("41246");
        assertThat(result.discovered().get(0).url()).isEqualTo("https://www.yourator.co/companies/aifian/jobs/41246");
        assertThat(result.discovered().get(0).payload().path("name").asText()).isEqualTo("Lead Software Engineer");
        assertThat(result.discovered().get(2).sourceJobId()).isEqualTo("4159");

        server.verify();
    }

    /**
     * 沒有頁數上限（見 add-crawl-improvements design.md），翻頁安全網之一是偵測
     * 「前後兩頁職缺集合完全相同」——分頁 API 常見的隱性 bug：頁碼超出真實範圍後
     * 悄悄重複回傳同一批內容，hasMore 完全不會反映這種情況。
     */
    @Test
    void stopsWhenConsecutivePagesReturnIdenticalJobSet() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess(fixture("yourator-list-page1.json"), MediaType.APPLICATION_JSON));
        // page=2 回傳跟 page=1 完全相同的 job id 集合，hasMore 仍是 true——模擬分頁卡住
        server.expect(requestTo(containsString("page=2")))
                .andRespond(withSuccess(fixture("yourator-list-page1-duplicate.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.YOURATOR, "TPE", List.of(), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> false);

        // 只採計第一頁（重複的第二頁被丟棄，不重複計入），不是失敗（沒有拋例外）
        assertThat(result.pagesScanned()).isEqualTo(1);
        assertThat(result.discovered()).hasSize(2);
        assertThat(meterRegistry.get("jobradar.scrape.anomaly")
                        .tag("source", "yourator")
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
                .andRespond(withSuccess(fixture("yourator-list-page2.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.YOURATOR, "TPE", List.of(), 120, true, null);

        scraper.scan(query, false, 1, ids -> false);

        assertThat(meterRegistry.get("jobradar.scrape.retry")
                        .tag("source", "yourator")
                        .tag("reason", "rate_limited")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    /**
     * add-crawl-improvements 部署後實測發現：沒有頁數上限之後一輪掃描要打的請求數
     * 變多，偶發的 I/O 逾時（connect/read timeout）在正式環境真的發生了——原本只有
     * 429 有重試，一次偶發逾時就讓整輪掃描全部作廢（見 YouratorListScraper 建構子
     * 註解）。這裡驗證 I/O 逾時現在會重試，不會直接放棄整輪掃描。
     */
    @Test
    void ioTimeoutRetryIsCounted() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andRespond(request -> {
                    throw new SocketTimeoutException("simulated read timeout");
                });
        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess(fixture("yourator-list-page2.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.YOURATOR, "TPE", List.of(), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> false);

        assertThat(result.discovered()).hasSize(1);
        assertThat(meterRegistry.get("jobradar.scrape.retry")
                        .tag("source", "yourator")
                        .tag("reason", "io_timeout")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    /**
     * 淺掃早停（見 architecture.md D6）：即使 hasMore=true，整頁都已知就提早停止，
     * 不會再打第 2 頁——只設一個 page=1 的 expectation，scraper 若誤打 page=2 會直接
     * 讓 MockRestServiceServer 拋出未預期請求的例外。
     */
    @Test
    void stopsEarlyWhenPageIsFullyKnownAndNotInDeepMode() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess(fixture("yourator-list-page1.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder.build(), new SimpleMeterRegistry(), new ScraperRequestExecutor(new SimpleMeterRegistry()));
        SearchQuery query = new SearchQuery(1, Source.YOURATOR, "TPE", List.of(), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> true);

        assertThat(result.pagesScanned()).isEqualTo(1);
        assertThat(result.discovered()).hasSize(2);
        assertThat(result.reachedEnd()).isTrue();
        server.verify();
    }

    /**
     * 深掃接續（見 architecture.md D6）：startPage 非 1 時直接從該頁開始打，不是每次
     * 都重新從第 1 頁翻——只設一個 page=3 的 expectation，驗證第一次請求就是打 page=3。
     */
    @Test
    void deepModeStartsFromGivenStartPage() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=3")))
                .andRespond(withSuccess(fixture("yourator-list-page2.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder.build(), new SimpleMeterRegistry(), new ScraperRequestExecutor(new SimpleMeterRegistry()));
        SearchQuery query = new SearchQuery(1, Source.YOURATOR, "TPE", List.of(), 120, true, null);

        ScanResult result = scraper.scan(query, true, 3, ids -> false);

        assertThat(result.pagesScanned()).isEqualTo(1);
        server.verify();
    }

    /**
     * 深掃被時間預算打斷（見 architecture.md D6）：maxScanDurationMinutes=0 讓第一次
     * 迴圈檢查就判定逾時，完全不會發出任何請求——server 沒設任何 expectation，
     * 若 scraper 誤打了請求，server.verify() 或請求本身會直接失敗。
     */
    @Test
    void deepModeCutShortByTimeBudgetReportsReachedEndFalse() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 0, 2000, 24, 0, Map.of());
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder.build(), new SimpleMeterRegistry(), new ScraperRequestExecutor(new SimpleMeterRegistry()));
        SearchQuery query = new SearchQuery(1, Source.YOURATOR, "TPE", List.of(), 120, true, null);

        ScanResult result = scraper.scan(query, true, 5, ids -> false);

        assertThat(result.reachedEnd()).isFalse();
        assertThat(result.nextPageToResume()).isEqualTo(5);
        assertThat(result.discovered()).isEmpty();
        server.verify();
    }

    /**
     * 疑似風控相關（見 architecture.md D19）：403/503 不重試，只設一個 expectation，
     * 如果誤重試會讓 MockRestServiceServer 拋出未預期請求的例外。
     */
    @Test
    void forbiddenResponseIsNotRetriedAndMarksBlocked() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.YOURATOR, "TPE", List.of(), 120, true, null);

        assertThatThrownBy(() -> scraper.scan(query, false, 1, ids -> false))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.get("jobradar.scrape.anomaly")
                        .tag("source", "yourator")
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
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder.build(), meterRegistry, new ScraperRequestExecutor(meterRegistry));
        SearchQuery query = new SearchQuery(1, Source.YOURATOR, "TPE", List.of(), 120, true, null);

        assertThatThrownBy(() -> scraper.scan(query, false, 1, ids -> false))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.get("jobradar.scrape.anomaly")
                        .tag("source", "yourator")
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
