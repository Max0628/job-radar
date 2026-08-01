package dev.jobradar.collector.scan;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScrapeRunRepository {

    private final JdbcClient jdbcClient;

    public ScrapeRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long startRun(String source, String queryCategories, Instant startedAt) {
        return jdbcClient.sql("""
                        INSERT INTO scrape_runs (source, query_categories, started_at, status)
                        VALUES (:source, :queryCategories, :startedAt, 'running')
                        RETURNING id
                        """)
                .param("source", source)
                .param("queryCategories", queryCategories)
                .param("startedAt", Timestamp.from(startedAt))
                .query(Long.class)
                .single();
    }

    /**
     * scanMode/terminatedEarly 供掃描摘要回報用（見 architecture.md D21）：
     * scanMode 是 "light"/"deep"，terminatedEarly 對應 {@code !ScanResult.reachedEnd()}。
     */
    public void finishRunSuccess(long runId, Instant finishedAt, int pagesScanned, int jobsSeen,
            String scanMode, boolean terminatedEarly) {
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
                .param("scanMode", scanMode)
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
