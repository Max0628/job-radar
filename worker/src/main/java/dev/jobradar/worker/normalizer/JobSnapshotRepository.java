package dev.jobradar.worker.normalizer;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobSnapshotRepository {

    private final JdbcClient jdbcClient;

    public JobSnapshotRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Append-only，重複訊息（同一秒內重放）以 (source, source_job_id, scraped_at) 唯一鍵擋下，
     * 不覆蓋既有快照（見 architecture.md D5）。
     */
    public void insertIgnore(
            String source, String sourceJobId, Instant scrapedAt, NormalizedJob normalized, String contentHash
    ) {
        jdbcClient.sql("""
                        INSERT INTO job_snapshots (source, source_job_id, scraped_at, title, company,
                                                    salary_min, salary_max, content_hash)
                        VALUES (:source, :sourceJobId, :scrapedAt, :title, :company, :salaryMin, :salaryMax, :contentHash)
                        ON CONFLICT (source, source_job_id, scraped_at) DO NOTHING
                        """)
                .param("source", source)
                .param("sourceJobId", sourceJobId)
                .param("scrapedAt", Timestamp.from(scrapedAt))
                .param("title", normalized.title())
                .param("company", normalized.company())
                .param("salaryMin", normalized.salaryMin())
                .param("salaryMax", normalized.salaryMax())
                .param("contentHash", contentHash)
                .update();
    }
}
