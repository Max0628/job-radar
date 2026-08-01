package dev.jobradar.collector.scan.cakeresume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.collector.scan.ScanResult;
import dev.jobradar.common.domain.SearchQuery;
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

class CakeResumeListScraperTest {

    @Test
    void scansPagesUntilCumulativeCountReachesTotalEntries() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"page\":1")))
                // filters.location（單數）是 no-op，實測確認正確欄位是 filters.locations
                // （複數、陣列），這裡斷言避免這個 bug 再次發生
                .andExpect(content().string(containsString("\"locations\":[\"Taipei\"]")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("\"location\":"))))
                .andRespond(withSuccess(fixture("cakeresume-search-page1.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"page\":2")))
                .andRespond(withSuccess(fixture("cakeresume-search-page2.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        CakeResumeListScraper scraper = new CakeResumeListScraper(properties, new ObjectMapper(), builder, new SimpleMeterRegistry());
        SearchQuery query = new SearchQuery(1, "cakeresume", "Taipei", List.of(), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> false);

        assertThat(result.pagesScanned()).isEqualTo(2);
        assertThat(result.discovered()).hasSize(3);
        // sourceJobId 用 path slug（真實 API 沒有 id 欄位，見端到端驗證發現）
        assertThat(result.discovered().get(0).sourceJobId())
                .isEqualTo("engineering-department-backend-engineer");
        assertThat(result.discovered().get(0).needsDetail()).isFalse();
        assertThat(result.discovered().get(0).detailUrl()).isNull();
        assertThat(result.discovered().get(0).url())
                .isEqualTo("https://www.cake.me/companies/cmoney/jobs/engineering-department-backend-engineer");
        assertThat(result.discovered().get(2).sourceJobId()).isEqualTo("devops-engineer-capital-layer");

        server.verify();
    }

    /**
     * 沒有頁數上限（見 add-crawl-improvements design.md），翻頁安全網之一是偵測
     * 「前後兩頁職缺集合完全相同」——分頁 API 常見的隱性 bug：頁碼超出真實範圍後
     * 悄悄重複回傳同一批內容，total_entries 比對完全不會反映這種情況。
     */
    @Test
    void stopsWhenConsecutivePagesReturnIdenticalJobSet() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(content().string(containsString("\"page\":1")))
                .andRespond(withSuccess(fixture("cakeresume-search-page1-large-total.json"), MediaType.APPLICATION_JSON));
        // page=2 回傳跟 page=1 完全相同的 path 集合，total_entries 仍顯示還有更多——模擬分頁卡住
        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(content().string(containsString("\"page\":2")))
                .andRespond(withSuccess(fixture("cakeresume-search-page1-large-total.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CakeResumeListScraper scraper = new CakeResumeListScraper(properties, new ObjectMapper(), builder, meterRegistry);
        SearchQuery query = new SearchQuery(1, "cakeresume", "Taipei", List.of(), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> false);

        // 只採計第一頁（重複的第二頁被丟棄，不重複計入），不是失敗（沒有拋例外）
        assertThat(result.pagesScanned()).isEqualTo(1);
        assertThat(result.discovered()).hasSize(2);
        assertThat(meterRegistry.get("jobradar.scrape.anomaly")
                        .tag("source", "cakeresume")
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

        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(content().string(containsString("\"page\":1")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(content().string(containsString("\"page\":1")))
                .andRespond(withSuccess(fixture("cakeresume-search-single-page.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CakeResumeListScraper scraper = new CakeResumeListScraper(properties, new ObjectMapper(), builder, meterRegistry);
        SearchQuery query = new SearchQuery(1, "cakeresume", "Taipei", List.of(), 120, true, null);

        scraper.scan(query, false, 1, ids -> false);

        assertThat(meterRegistry.get("jobradar.scrape.retry")
                        .tag("source", "cakeresume")
                        .tag("reason", "rate_limited")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    /**
     * add-crawl-improvements 部署後實測發現：沒有頁數上限之後一輪掃描要打的請求數
     * 變多，偶發的 I/O 逾時（connect/read timeout）在正式環境真的發生了——原本只有
     * 429 有重試，一次偶發逾時就讓整輪掃描全部作廢（見 CakeResumeListScraper 建構子
     * 註解）。這裡驗證 I/O 逾時現在會重試，不會直接放棄整輪掃描。
     */
    @Test
    void ioTimeoutRetryIsCounted() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(content().string(containsString("\"page\":1")))
                .andRespond(request -> {
                    throw new SocketTimeoutException("simulated read timeout");
                });
        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(content().string(containsString("\"page\":1")))
                .andRespond(withSuccess(fixture("cakeresume-search-single-page.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CakeResumeListScraper scraper = new CakeResumeListScraper(properties, new ObjectMapper(), builder, meterRegistry);
        SearchQuery query = new SearchQuery(1, "cakeresume", "Taipei", List.of(), 120, true, null);

        ScanResult result = scraper.scan(query, false, 1, ids -> false);

        assertThat(result.discovered()).hasSize(2);
        assertThat(meterRegistry.get("jobradar.scrape.retry")
                        .tag("source", "cakeresume")
                        .tag("reason", "io_timeout")
                        .counter()
                        .count())
                .isEqualTo(1.0);
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

        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(content().string(containsString("\"page\":1")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CakeResumeListScraper scraper = new CakeResumeListScraper(properties, new ObjectMapper(), builder, meterRegistry);
        SearchQuery query = new SearchQuery(1, "cakeresume", "Taipei", List.of(), 120, true, null);

        assertThatThrownBy(() -> scraper.scan(query, false, 1, ids -> false))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.get("jobradar.scrape.anomaly")
                        .tag("source", "cakeresume")
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

        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(content().string(containsString("\"page\":1")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CakeResumeListScraper scraper = new CakeResumeListScraper(properties, new ObjectMapper(), builder, meterRegistry);
        SearchQuery query = new SearchQuery(1, "cakeresume", "Taipei", List.of(), 120, true, null);

        assertThatThrownBy(() -> scraper.scan(query, false, 1, ids -> false))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.get("jobradar.scrape.anomaly")
                        .tag("source", "cakeresume")
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
