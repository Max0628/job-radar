package dev.jobradar.worker.reporter;

import java.time.Instant;

/**
 * scrape_runs 裡「已成功結束、還沒回報過」的一列（見 architecture.md D21）。
 */
public record UnreportedScrapeRun(
        long id,
        String source,
        String scanMode,
        boolean terminatedEarly,
        Instant startedAt,
        Instant finishedAt,
        int pagesScanned,
        int jobsSeen
) {
}
