package dev.jobradar.collector.scan;

import dev.jobradar.common.domain.SearchQuery;
import dev.jobradar.common.envelope.DiscoveredEnvelope;
import dev.jobradar.common.kafka.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public ScanService(
            List<JobListScraper> scrapers,
            ScrapeCursorRepository cursorRepository,
            ScrapeRunRepository runRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            MeterRegistry meterRegistry
    ) {
        this.scrapersBySource = scrapers.stream().collect(Collectors.toMap(JobListScraper::source, s -> s));
        this.cursorRepository = cursorRepository;
        this.runRepository = runRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    public void runScan(SearchQuery query) {
        JobListScraper scraper = scrapersBySource.get(query.source());
        if (scraper == null) {
            log.warn("No scraper registered for source={}, skipping query id={}", query.source(), query.id());
            return;
        }

        String queryCategories = query.categories() == null ? "" : String.join(",", query.categories());
        Instant startedAt = Instant.now();
        long runId = runRepository.startRun(query.source(), queryCategories, startedAt);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            ScanResult result = scraper.scan(query);
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

            cursorRepository.updateAfterScan(query.id(), scrapedAt, result.pagesScanned());
            runRepository.finishRunSuccess(runId, Instant.now(), result.pagesScanned(), result.discovered().size());

            meterRegistry.counter("jobradar.scan", "source", query.source(), "result", "success").increment();
            meterRegistry.counter("jobradar.jobs.discovered", "source", query.source())
                    .increment(result.discovered().size());
            sample.stop(Timer.builder("jobradar.scan.duration")
                    .tag("source", query.source())
                    .register(meterRegistry));

            log.info("Scan finished source={} categories={} pages={} jobsDiscovered={}",
                    query.source(), queryCategories, result.pagesScanned(), result.discovered().size());
        } catch (Exception e) {
            log.error("Scan failed source={} categories={}", query.source(), queryCategories, e);
            runRepository.finishRunFailed(runId, Instant.now(), e.getMessage());

            meterRegistry.counter("jobradar.scan", "source", query.source(), "result", "failure").increment();
            sample.stop(Timer.builder("jobradar.scan.duration")
                    .tag("source", query.source())
                    .register(meterRegistry));
        }
    }
}
