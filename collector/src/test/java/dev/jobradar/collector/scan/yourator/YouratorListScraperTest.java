package dev.jobradar.collector.scan.yourator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.collector.scan.ScanResult;
import dev.jobradar.common.domain.SearchQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24);
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder, new SimpleMeterRegistry());
        SearchQuery query = new SearchQuery(1, "yourator", "TPE", List.of(), 120, true);

        ScanResult result = scraper.scan(query);

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

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder, meterRegistry);
        SearchQuery query = new SearchQuery(1, "yourator", "TPE", List.of(), 120, true);

        ScanResult result = scraper.scan(query);

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

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder, meterRegistry);
        SearchQuery query = new SearchQuery(1, "yourator", "TPE", List.of(), 120, true);

        scraper.scan(query);

        assertThat(meterRegistry.get("jobradar.scrape.retry")
                        .tag("source", "yourator")
                        .tag("reason", "rate_limited")
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
