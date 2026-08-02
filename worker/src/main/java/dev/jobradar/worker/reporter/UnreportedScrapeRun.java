package dev.jobradar.worker.reporter;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.jobradar.common.domain.ScanMode;
import java.time.Instant;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * scrape_runs 裡「已成功結束、還沒回報過」的一列（見 architecture.md D21）。
 */
@Value
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class UnreportedScrapeRun {
    long id;
    String source;
    ScanMode scanMode;
    boolean terminatedEarly;
    Instant startedAt;
    Instant finishedAt;
    int pagesScanned;
    int jobsSeen;
}
