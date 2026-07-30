package dev.jobradar.collector.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.domain.SearchQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 驗證 add-business-metrics-and-alerting 埋的 jobradar.scan / jobradar.jobs.discovered /
 * jobradar.scan.duration，在成功與失敗兩條路徑都正確記錄，且 label 值域符合 design.md
 * 的規則（只有 source/result，不含 keyword 等無界值）。
 */
class ScanServiceTest {

    private final ScrapeCursorRepository cursorRepository = mock(ScrapeCursorRepository.class);
    private final ScrapeRunRepository runRepository = mock(ScrapeRunRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    @Test
    void successfulScanRecordsScanAndDiscoveredMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JobListScraper scraper = new JobListScraper() {
            @Override
            public String source() {
                return "yourator";
            }

            @Override
            public ScanResult scan(SearchQuery query) {
                JsonNode payload = new ObjectMapper().createObjectNode();
                return new ScanResult(
                        List.of(new DiscoveredJob("1", "http://example.com/1", payload),
                                new DiscoveredJob("2", "http://example.com/2", payload)),
                        1);
            }
        };
        when(runRepository.startRun(anyString(), anyString(), any(Instant.class))).thenReturn(1L);

        ScanService service = new ScanService(List.of(scraper), cursorRepository, runRepository, kafkaTemplate, meterRegistry);
        SearchQuery query = new SearchQuery(1, "yourator", null, List.of(), 120, true);

        service.runScan(query);

        assertThat(meterRegistry.get("jobradar.scan")
                        .tag("source", "yourator")
                        .tag("result", "success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("jobradar.jobs.discovered")
                        .tag("source", "yourator")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.get("jobradar.scan.duration")
                        .tag("source", "yourator")
                        .timer()
                        .count())
                .isEqualTo(1L);
    }

    @Test
    void failedScanRecordsFailureResultNotSuccess() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JobListScraper scraper = new JobListScraper() {
            @Override
            public String source() {
                return "yourator";
            }

            @Override
            public ScanResult scan(SearchQuery query) {
                throw new IllegalStateException("simulated scrape failure");
            }
        };
        when(runRepository.startRun(anyString(), anyString(), any(Instant.class))).thenReturn(1L);

        ScanService service = new ScanService(List.of(scraper), cursorRepository, runRepository, kafkaTemplate, meterRegistry);
        SearchQuery query = new SearchQuery(1, "yourator", null, List.of(), 120, true);

        service.runScan(query);

        assertThat(meterRegistry.get("jobradar.scan")
                        .tag("source", "yourator")
                        .tag("result", "failure")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("jobradar.scan")
                        .tag("source", "yourator")
                        .tag("result", "success")
                        .counter())
                .isNull();
        assertThat(meterRegistry.find("jobradar.jobs.discovered").counter()).isNull();
    }
}
