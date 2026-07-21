package dev.jobradar.collector.scan.cakeresume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.collector.scan.ScanResult;
import dev.jobradar.common.domain.SearchQuery;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
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

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24);
        CakeResumeListScraper scraper = new CakeResumeListScraper(properties, new ObjectMapper(), builder);
        SearchQuery query = new SearchQuery(1, "cakeresume", "後端工程師", "Taipei", List.of(), 10, 120, true);

        ScanResult result = scraper.scan(query);

        assertThat(result.pagesScanned()).isEqualTo(2);
        assertThat(result.discovered()).hasSize(3);
        // sourceJobId 用 path slug（真實 API 沒有 id 欄位，見端到端驗證發現）
        assertThat(result.discovered().get(0).sourceJobId())
                .isEqualTo("engineering-department-backend-engineer");
        assertThat(result.discovered().get(0).needsDetail()).isFalse();
        assertThat(result.discovered().get(0).detailUrl()).isNull();
        assertThat(result.discovered().get(0).url())
                .isEqualTo("https://www.cake.me/jobs/engineering-department-backend-engineer");
        assertThat(result.discovered().get(2).sourceJobId()).isEqualTo("devops-engineer-capital-layer");

        server.verify();
    }

    @Test
    void stopsAtMaxPagesEvenIfMoreEntriesRemain() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(content().string(containsString("\"page\":1")))
                .andRespond(withSuccess(fixture("cakeresume-search-page1.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24);
        CakeResumeListScraper scraper = new CakeResumeListScraper(properties, new ObjectMapper(), builder);
        SearchQuery query = new SearchQuery(1, "cakeresume", "後端工程師", "Taipei", List.of(), 1, 120, true);

        ScanResult result = scraper.scan(query);

        assertThat(result.pagesScanned()).isEqualTo(1);
        assertThat(result.discovered()).hasSize(2);
        server.verify();
    }

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
