package dev.jobradar.collector.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.domain.SearchQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 驗證 add-business-metrics-and-alerting 埋的 jobradar.scan / jobradar.jobs.discovered /
 * jobradar.scan.duration，在成功與失敗兩條路徑都正確記錄，且 label 值域符合 design.md
 * 的規則（只有 source/result，不含 keyword 等無界值）。
 *
 * cursorRepository 未 stub 的 findLastDeepScanCompletedAt() 回傳 Optional.empty()
 * （Mockito 對 Optional 回傳型別的預設行為），代表這裡的測試情境一律跑深掃模式——
 * 這些測試本來就不是在驗證淺掃/深掃邏輯本身（見 architecture.md D6 那組測試），
 * 這裡只關心 scan 結果的 metrics 記錄是否正確，跑哪個模式不影響這件事。
 */
class ScanServiceTest {

    private final ScrapeCursorRepository cursorRepository = mock(ScrapeCursorRepository.class);
    private final ScrapeRunRepository runRepository = mock(ScrapeRunRepository.class);
    private final JobExistenceRepository jobExistenceRepository = mock(JobExistenceRepository.class);
    private final SearchQueryRepository searchQueryRepository = mock(SearchQueryRepository.class);
    private final CollectorScanProperties properties =
            new CollectorScanProperties(300_000, 0, "test-agent", 0, 0, 24, 3, 15, 2000, 24, 45, Map.of());
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
            public ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown) {
                JsonNode payload = new ObjectMapper().createObjectNode();
                return new ScanResult(
                        List.of(new DiscoveredJob("1", "http://example.com/1", payload),
                                new DiscoveredJob("2", "http://example.com/2", payload)),
                        1, true, 2);
            }
        };
        when(runRepository.startRun(anyString(), anyString(), any(Instant.class))).thenReturn(1L);

        ScanService service = new ScanService(
                List.of(scraper), cursorRepository, runRepository, jobExistenceRepository, searchQueryRepository,
                properties, kafkaTemplate, meterRegistry);
        SearchQuery query = new SearchQuery(1, "yourator", null, List.of(), 120, true, null);

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
            public ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown) {
                throw new IllegalStateException("simulated scrape failure");
            }
        };
        when(runRepository.startRun(anyString(), anyString(), any(Instant.class))).thenReturn(1L);

        ScanService service = new ScanService(
                List.of(scraper), cursorRepository, runRepository, jobExistenceRepository, searchQueryRepository,
                properties, kafkaTemplate, meterRegistry);
        SearchQuery query = new SearchQuery(1, "yourator", null, List.of(), 120, true, null);

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

    /**
     * 疑似被來源網站封鎖（見 add-104-source/design.md「自動關閉」決策）：ScanService
     * 收到 SourceBlockedException 要呼叫 disableAllForSource，且仍照失敗路徑記錄
     * metrics（不是額外的第三種結果）。
     */
    @Test
    void blockedScanDisablesAllQueriesForSource() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JobListScraper scraper = new JobListScraper() {
            @Override
            public String source() {
                return "104";
            }

            @Override
            public ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown) {
                throw new dev.jobradar.common.source.SourceBlockedException("104", "104 returned 403", null);
            }
        };
        when(runRepository.startRun(anyString(), anyString(), any(Instant.class))).thenReturn(1L);

        ScanService service = new ScanService(
                List.of(scraper), cursorRepository, runRepository, jobExistenceRepository, searchQueryRepository,
                properties, kafkaTemplate, meterRegistry);
        SearchQuery query = new SearchQuery(1, "104", null, List.of("2007001016"), 120, true, null);

        service.runScan(query);

        verify(searchQueryRepository).disableAllForSource(eq("104"), anyString());
        assertThat(meterRegistry.get("jobradar.scan")
                        .tag("source", "104")
                        .tag("result", "failure")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    /**
     * 淺掃/深掃合併邏輯（見 architecture.md D6）：距離上次深掃完成很近（這裡設成
     * 剛剛才完成），這輪應該跑淺掃，只更新 last_scanned_at，完全不碰深掃專屬的
     * last_page_scanned/last_deep_scan_completed_at。
     */
    @Test
    void lightModeUpdatesCursorWithoutTouchingDeepScanState() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(cursorRepository.findLastDeepScanCompletedAt(1L)).thenReturn(Optional.of(Instant.now()));
        JobListScraper scraper = new JobListScraper() {
            @Override
            public String source() {
                return "yourator";
            }

            @Override
            public ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown) {
                assertThat(deepMode).isFalse();
                JsonNode payload = new ObjectMapper().createObjectNode();
                return new ScanResult(List.of(new DiscoveredJob("1", "http://example.com/1", payload)), 1, true, 2);
            }
        };
        when(runRepository.startRun(anyString(), anyString(), any(Instant.class))).thenReturn(1L);

        ScanService service = new ScanService(
                List.of(scraper), cursorRepository, runRepository, jobExistenceRepository, searchQueryRepository,
                properties, kafkaTemplate, meterRegistry);
        SearchQuery query = new SearchQuery(1, "yourator", null, List.of(), 120, true, null);

        service.runScan(query);

        verify(cursorRepository).updateAfterLightScan(eq(1L), any(Instant.class));
        verify(cursorRepository, never()).updateAfterDeepScan(anyLong(), any(Instant.class), anyBoolean(), anyInt());
    }

    /**
     * 深掃真的翻完（reachedEnd=true）：接續頁碼歸零、深掃完成時間更新（見
     * ScrapeCursorRepository.updateAfterDeepScan）。cursorRepository 沒 stub
     * findLastDeepScanCompletedAt，預設回傳 Optional.empty()，代表這輪是深掃。
     */
    @Test
    void deepModeReachingEndResetsPageCursorAndRecordsCompletion() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JobListScraper scraper = new JobListScraper() {
            @Override
            public String source() {
                return "yourator";
            }

            @Override
            public ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown) {
                assertThat(deepMode).isTrue();
                JsonNode payload = new ObjectMapper().createObjectNode();
                return new ScanResult(List.of(new DiscoveredJob("1", "http://example.com/1", payload)), 3, true, 4);
            }
        };
        when(runRepository.startRun(anyString(), anyString(), any(Instant.class))).thenReturn(1L);

        ScanService service = new ScanService(
                List.of(scraper), cursorRepository, runRepository, jobExistenceRepository, searchQueryRepository,
                properties, kafkaTemplate, meterRegistry);
        SearchQuery query = new SearchQuery(1, "yourator", null, List.of(), 120, true, null);

        service.runScan(query);

        verify(cursorRepository).updateAfterDeepScan(eq(1L), any(Instant.class), eq(true), eq(4));
    }

    /**
     * 深掃被時間預算打斷（reachedEnd=false）：接續頁碼存下一輪該從哪一頁開始，
     * 深掃完成時間不更新（見 ScrapeCursorRepository.updateAfterDeepScan）。
     */
    @Test
    void deepModeCutShortSavesResumePageWithoutMarkingComplete() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JobListScraper scraper = new JobListScraper() {
            @Override
            public String source() {
                return "yourator";
            }

            @Override
            public ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown) {
                return new ScanResult(List.of(), 2, false, 7);
            }
        };
        when(runRepository.startRun(anyString(), anyString(), any(Instant.class))).thenReturn(1L);

        ScanService service = new ScanService(
                List.of(scraper), cursorRepository, runRepository, jobExistenceRepository, searchQueryRepository,
                properties, kafkaTemplate, meterRegistry);
        SearchQuery query = new SearchQuery(1, "yourator", null, List.of(), 120, true, null);

        service.runScan(query);

        verify(cursorRepository).updateAfterDeepScan(eq(1L), any(Instant.class), eq(false), eq(7));
    }
}
