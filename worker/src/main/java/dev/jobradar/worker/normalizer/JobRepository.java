package dev.jobradar.worker.normalizer;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

    private final JdbcClient jdbcClient;

    public JobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 冪等 upsert（見 architecture.md D5）。回傳 true 代表這是全新一列（第一次看到這筆職缺），
     * 只有這種情況 normalizer 才會發 NEW 事件；重複訊息一律回傳 false，不會重複觸發事件。
     */
    public boolean upsert(
            String source, String sourceJobId, String url, NormalizedJob normalized,
            String contentHash, String attrsJson, Instant seenAt
    ) {
        Boolean inserted = jdbcClient.sql("""
                        INSERT INTO jobs (source, source_job_id, title, company, salary_min, salary_max,
                                          salary_currency, url, content_hash, attrs, first_seen_at, last_seen_at)
                        VALUES (:source, :sourceJobId, :title, :company, :salaryMin, :salaryMax,
                                :salaryCurrency, :url, :contentHash, :attrs, :seenAt, :seenAt)
                        ON CONFLICT (source, source_job_id) DO UPDATE SET
                            title = excluded.title,
                            company = excluded.company,
                            salary_min = excluded.salary_min,
                            salary_max = excluded.salary_max,
                            salary_currency = excluded.salary_currency,
                            content_hash = excluded.content_hash,
                            attrs = excluded.attrs,
                            last_seen_at = excluded.last_seen_at
                        RETURNING (xmax = 0) AS inserted
                        """)
                .param("source", source)
                .param("sourceJobId", sourceJobId)
                .param("title", normalized.title())
                .param("company", normalized.company())
                .param("salaryMin", normalized.salaryMin())
                .param("salaryMax", normalized.salaryMax())
                .param("salaryCurrency", normalized.salaryCurrency())
                .param("url", url)
                .param("contentHash", contentHash)
                .param("attrs", PgJson.jsonb(attrsJson))
                .param("seenAt", Timestamp.from(seenAt))
                .query(Boolean.class)
                .single();

        return Boolean.TRUE.equals(inserted);
    }
}
