package dev.jobradar.worker.reporter;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 掃描摘要回報用（見 architecture.md D21）。只查 `status = 'success'` 的 run——失敗的
 * run 沒有 scan_mode/pages_scanned/jobs_seen 資料（finishRunFailed 不寫這些欄位），
 * 且失敗另外走 source-error-alerting 的告警路徑，不是這裡的範圍。
 */
@Repository
@RequiredArgsConstructor
public class ScrapeRunReportRepository {

    private final JdbcClient jdbcClient;

    public List<UnreportedScrapeRun> findUnreportedFinishedRuns(Instant before) {
        return jdbcClient.sql("""
                        SELECT id, source, scan_mode, terminated_early, started_at, finished_at,
                               pages_scanned, jobs_seen
                        FROM scrape_runs
                        WHERE status = 'success'
                          AND finished_at IS NOT NULL
                          AND finished_at < :before
                          AND report_sent_at IS NULL
                        ORDER BY finished_at
                        """)
                .param("before", Timestamp.from(before))
                .query((rs, rowNum) -> new UnreportedScrapeRun(
                        rs.getLong("id"),
                        rs.getString("source"),
                        rs.getString("scan_mode"),
                        rs.getBoolean("terminated_early"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at").toInstant(),
                        rs.getInt("pages_scanned"),
                        rs.getInt("jobs_seen")))
                .list();
    }

    /**
     * 新增筆數的簡化版算法（見 architecture.md D21）：不精確關聯每筆訊息屬於哪一輪
     * 掃描，直接查這個時間窗內、這個來源的 first_seen_at 筆數。
     */
    public int countNewJobs(String source, Instant from, Instant to) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM jobs
                        WHERE source = :source AND first_seen_at BETWEEN :from AND :to
                        """)
                .param("source", source)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(Integer.class)
                .single();
    }

    public void markReported(long runId) {
        jdbcClient.sql("UPDATE scrape_runs SET report_sent_at = :now WHERE id = :id")
                .param("now", Timestamp.from(Instant.now()))
                .param("id", runId)
                .update();
    }
}
