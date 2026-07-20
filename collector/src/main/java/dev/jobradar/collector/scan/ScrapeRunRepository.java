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

    public long startRun(String source, String queryKeyword, Instant startedAt) {
        return jdbcClient.sql("""
                        INSERT INTO scrape_runs (source, query_keyword, started_at, status)
                        VALUES (:source, :queryKeyword, :startedAt, 'running')
                        RETURNING id
                        """)
                .param("source", source)
                .param("queryKeyword", queryKeyword)
                .param("startedAt", Timestamp.from(startedAt))
                .query(Long.class)
                .single();
    }

    public void finishRunSuccess(long runId, Instant finishedAt, int pagesScanned, int jobsSeen) {
        jdbcClient.sql("""
                        UPDATE scrape_runs
                        SET finished_at = :finishedAt, pages_scanned = :pagesScanned,
                            jobs_seen = :jobsSeen, status = 'success'
                        WHERE id = :id
                        """)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("pagesScanned", pagesScanned)
                .param("jobsSeen", jobsSeen)
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
