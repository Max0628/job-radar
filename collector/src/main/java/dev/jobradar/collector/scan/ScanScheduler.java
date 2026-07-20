package dev.jobradar.collector.scan;

import dev.jobradar.common.domain.SearchQuery;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final SearchQueryRepository searchQueryRepository;
    private final ScrapeCursorRepository cursorRepository;
    private final ScanService scanService;
    private final CollectorScanProperties properties;
    private final Random random = new Random();

    public ScanScheduler(
            SearchQueryRepository searchQueryRepository,
            ScrapeCursorRepository cursorRepository,
            ScanService scanService,
            CollectorScanProperties properties
    ) {
        this.searchQueryRepository = searchQueryRepository;
        this.cursorRepository = cursorRepository;
        this.scanService = scanService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${collector.scan.tick-interval-millis}", initialDelayString = "10000")
    public void tick() {
        for (SearchQuery query : searchQueryRepository.findAllEnabled()) {
            try {
                if (isDue(query)) {
                    scanService.runScan(query);
                }
            } catch (Exception e) {
                log.error("Unexpected error while checking/running scan for query id={}", query.id(), e);
            }
        }
    }

    private boolean isDue(SearchQuery query) {
        Optional<Instant> lastScannedAt = cursorRepository.findLastScannedAt(query.id());
        if (lastScannedAt.isEmpty()) {
            return true;
        }

        long jitterSeconds = properties.jitterSeconds() > 0 ? random.nextInt(properties.jitterSeconds()) : 0;
        Instant dueAt = lastScannedAt.get()
                .plusSeconds((long) query.intervalMinutes() * 60)
                .plusSeconds(jitterSeconds);
        return !Instant.now().isBefore(dueAt);
    }
}
