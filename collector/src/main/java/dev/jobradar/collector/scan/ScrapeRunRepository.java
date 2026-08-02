package dev.jobradar.collector.scan;

import dev.jobradar.common.domain.ScanMode;
import dev.jobradar.common.source.Source;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ScrapeRunRepository {

    private final JdbcClient jdbcClient;

    public long startRun(Source source, String queryCategories, Instant startedAt) {
        return jdbcClient.sql("""
                        INSERT INTO scrape_runs (source, query_categories, started_at, status)
                        VALUES (:source, :queryCategories, :startedAt, 'running')
                        RETURNING id
                        """)
                .param("source", source.value())
                .param("queryCategories", queryCategories)
                .param("startedAt", Timestamp.from(startedAt))
                .query(Long.class)
                .single();
    }

    /**
     * scanMode/terminatedEarly 供掃描摘要回報用（見 architecture.md D21）：
     * terminatedEarly 對應 {@code !ScanResult.reachedEnd()}。
     */
    public void finishRunSuccess(long runId, Instant finishedAt, int pagesScanned, int jobsSeen,
            ScanMode scanMode, boolean terminatedEarly) {
        jdbcClient.sql("""
                        UPDATE scrape_runs
                        SET finished_at = :finishedAt, pages_scanned = :pagesScanned,
                            jobs_seen = :jobsSeen, status = 'success',
                            scan_mode = :scanMode, terminated_early = :terminatedEarly
                        WHERE id = :id
                        """)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("pagesScanned", pagesScanned)
                .param("jobsSeen", jobsSeen)
                .param("scanMode", scanMode.dbValue())
                .param("terminatedEarly", terminatedEarly)
                .param("id", runId)
                .update();
    }

    public void finishRunFailed(long runId, Instant finishedAt, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE scrape_runs
                        SET finished_at = :finishedAt, status = 'failed', error_message = :errorMessage
                        WHERE id = :id
                        """)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("errorMessage", errorMessage)
                .param("id", runId)
                .update();
    }
}
