package dev.jobradar.collector.scan;

import dev.jobradar.common.domain.SearchQuery;
import dev.jobradar.common.envelope.DiscoveredEnvelope;
import dev.jobradar.common.kafka.Topics;
import dev.jobradar.common.source.SourceBlockedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final Map<String, JobListScraper> scrapersBySource;
    private final ScrapeCursorRepository cursorRepository;
    private final ScrapeRunRepository runRepository;
    private final JobExistenceRepository jobExistenceRepository;
    private final SearchQueryRepository searchQueryRepository;
    private final CollectorScanProperties properties;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public ScanService(
            List<JobListScraper> scrapers,
            ScrapeCursorRepository cursorRepository,
            ScrapeRunRepository runRepository,
            JobExistenceRepository jobExistenceRepository,
            SearchQueryRepository searchQueryRepository,
            CollectorScanProperties properties,
            KafkaTemplate<String, Object> kafkaTemplate,
            MeterRegistry meterRegistry
    ) {
        this.scrapersBySource = scrapers.stream().collect(Collectors.toMap(JobListScraper::source, s -> s));
        this.cursorRepository = cursorRepository;
        this.runRepository = runRepository;
        this.jobExistenceRepository = jobExistenceRepository;
        this.searchQueryRepository = searchQueryRepository;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    public void runScan(SearchQuery query) {
        JobListScraper scraper = scrapersBySource.get(query.source());
        if (scraper == null) {
            log.warn("No scraper registered for source={}, skipping query id={}", query.source(), query.id());
            return;
        }

        // 淺掃/深掃合併成一套邏輯的模式判斷（見 architecture.md D6）：距離上次深掃完成
        // 超過設定時數（或從未深掃過）就這次跑深掃，否則跑淺掃。
        boolean deepMode = isDeepScanDue(query.id());
        int startPage = deepMode
                ? cursorRepository.findLastPageScanned(query.id()).filter(p -> p != null && p > 0).orElse(1)
                : 1;
        // deepMode=true 時這個 predicate 不會被 scraper 呼叫，給一個不查資料庫的無害版本；
        // 淺掃才需要真的查——early termination 只看「存不存在」，不比對內容（見
        // JobExistenceRepository 類別註解）。
        Predicate<Set<String>> pageIsFullyKnown = deepMode
                ? ids -> false
                : ids -> ids.stream().allMatch(id -> jobExistenceRepository.exists(query.source(), id));

        String queryCategories = query.categories() == null ? "" : String.join(",", query.categories());
        Instant startedAt = Instant.now();
        long runId = runRepository.startRun(query.source(), queryCategories, startedAt);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            ScanResult result = scraper.scan(query, deepMode, startPage, pageIsFullyKnown);
            Instant scrapedAt = Instant.now();

            for (DiscoveredJob job : result.discovered()) {
                DiscoveredEnvelope envelope = new DiscoveredEnvelope(
                        query.source(),
                        job.sourceJobId(),
                        scrapedAt,
                        job.url(),
                        job.needsDetail(),
                        job.detailUrl(),
                        job.payload()
                );
                String key = query.source() + ":" + job.sourceJobId();
                kafkaTemplate.send(Topics.JOBS_DISCOVERED, key, envelope);
            }

            if (deepMode) {
                cursorRepository.updateAfterDeepScan(query.id(), scrapedAt, result.reachedEnd(), result.nextPageToResume());
            } else {
                cursorRepository.updateAfterLightScan(query.id(), scrapedAt);
            }
            runRepository.finishRunSuccess(runId, Instant.now(), result.pagesScanned(), result.discovered().size(),
                    deepMode ? "deep" : "light", !result.reachedEnd());

            meterRegistry.counter("jobradar.scan", "source", query.source(), "result", "success").increment();
            meterRegistry.counter("jobradar.jobs.discovered", "source", query.source())
                    .increment(result.discovered().size());
            sample.stop(Timer.builder("jobradar.scan.duration")
                    .tag("source", query.source())
                    .register(meterRegistry));

            log.info("Scan finished source={} categories={} pages={} jobsDiscovered={}",
                    query.source(), queryCategories, result.pagesScanned(), result.discovered().size());
        } catch (SourceBlockedException e) {
            // 疑似被來源網站封鎖：自動停用該來源所有查詢，避免下個排程週期繼續打、
            // 拉高被鎖 IP 的風險（見 add-104-source/design.md「自動關閉」決策，目前
            // 只有 104 會拋這個例外，Yourator/CakeResume 不受影響）。重新啟用是純手動。
            log.error("Scan blocked source={} categories={}, disabling all queries for this source",
                    query.source(), queryCategories, e);
            searchQueryRepository.disableAllForSource(query.source(), e.getMessage());
            recordFailure(runId, query.source(), sample, e);
        } catch (Exception e) {
            log.error("Scan failed source={} categories={}", query.source(), queryCategories, e);
            recordFailure(runId, query.source(), sample, e);
        }
    }

    private void recordFailure(long runId, String source, Timer.Sample sample, Exception e) {
        runRepository.finishRunFailed(runId, Instant.now(), e.getMessage());
        meterRegistry.counter("jobradar.scan", "source", source, "result", "failure").increment();
        sample.stop(Timer.builder("jobradar.scan.duration")
                .tag("source", source)
                .register(meterRegistry));
    }

    private boolean isDeepScanDue(long searchQueryId) {
        return cursorRepository.findLastDeepScanCompletedAt(searchQueryId)
                .map(lastCompleted -> !Instant.now().isBefore(
                        lastCompleted.plus(Duration.ofHours(properties.deepScanIntervalHours()))))
                .orElse(true);
    }
}
