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
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
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
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder);
        SearchQuery query = new SearchQuery(1, "yourator", "devops", "TPE", List.of(), 10, 120, true);

        ScanResult result = scraper.scan(query);

        assertThat(result.pagesScanned()).isEqualTo(2);
        assertThat(result.discovered()).hasSize(3);
        assertThat(result.discovered().get(0).sourceJobId()).isEqualTo("41246");
        assertThat(result.discovered().get(0).url()).isEqualTo("https://www.yourator.co/companies/aifian/jobs/41246");
        assertThat(result.discovered().get(0).payload().path("name").asText()).isEqualTo("Lead Software Engineer");
        assertThat(result.discovered().get(2).sourceJobId()).isEqualTo("4159");

        server.verify();
    }

    @Test
    void stopsAtMaxPagesEvenIfHasMoreIsTrue() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess(fixture("yourator-list-page1.json"), MediaType.APPLICATION_JSON));

        CollectorScanProperties properties = new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24);
        YouratorListScraper scraper = new YouratorListScraper(properties, new ObjectMapper(), builder);
        SearchQuery query = new SearchQuery(1, "yourator", "devops", "TPE", List.of(), 1, 120, true);

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
