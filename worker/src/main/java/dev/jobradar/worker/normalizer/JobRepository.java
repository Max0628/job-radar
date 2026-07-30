package dev.jobradar.worker.normalizer;

import dev.jobradar.common.db.PgJson;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

    private final JdbcClient jdbcClient;

    public JobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 查目前 `jobs` 表上這筆職缺的 content_hash，供 normalizer 判斷內容是否真的變了
     * （見 add-crawl-improvements design.md）。回傳空值代表這筆職缺從沒見過（真正的新職缺），
     * 呼叫端據此決定要不要真的寫 job_snapshots／raw_documents，避免同一份內容被反覆掃到時
     * 一直堆重複快照——`jobs` 本身的 upsert 不受這個判斷影響，仍然每次都跑，維持
     * `last_seen_at` 更新（見 D12：closed sweep 依賴的資料不可事後補）。
     */
    public Optional<String> findContentHash(String source, String sourceJobId) {
        return jdbcClient.sql("SELECT content_hash FROM jobs WHERE source = :source AND source_job_id = :sourceJobId")
                .param("source", source)
                .param("sourceJobId", sourceJobId)
                .query(String.class)
                .optional();
    }

    /**
     * 冪等 upsert（見 architecture.md D5）。回傳 true 代表這是全新一列（第一次看到這筆職缺），
     * 只有這種情況 normalizer 才會發 NEW 事件；重複訊息一律回傳 false，不會重複觸發事件。
     *
     * status 邏輯（見 Phase 002 design.md D6）：新插入固定 'NEW'；既有職缺被重新看到時，
     * 'NEW'/'CLOSED' 都轉成 'ACTIVE'（CLOSED 代表未來 closed sweep 判定下架後又重新出現，
     * 視為復活），其餘狀態維持不變。
     */
    public boolean upsert(
            String source, String sourceJobId, String url, NormalizedJob normalized,
            String contentHash, String attrsJson, Instant seenAt
    ) {
        Boolean inserted = jdbcClient.sql("""
                        INSERT INTO jobs (source, source_job_id, title, company, salary_min, salary_max,
                                          salary_currency, url, content_hash, attrs, status,
                                          employment_type, seniority_level, job_type, lang_name,
                                          min_work_exp_year, number_of_openings, city, district,
                                          posted_at, first_seen_at, last_seen_at)
                        VALUES (:source, :sourceJobId, :title, :company, :salaryMin, :salaryMax,
                                :salaryCurrency, :url, :contentHash, :attrs, 'NEW',
                                :employmentType, :seniorityLevel, :jobType, :langName,
                                :minWorkExpYear, :numberOfOpenings, :city, :district,
                                :postedAt, :seenAt, :seenAt)
                        ON CONFLICT (source, source_job_id) DO UPDATE SET
                            url = excluded.url,
                            title = excluded.title,
                            company = excluded.company,
                            salary_min = excluded.salary_min,
                            salary_max = excluded.salary_max,
                            salary_currency = excluded.salary_currency,
                            content_hash = excluded.content_hash,
                            attrs = excluded.attrs,
                            employment_type = excluded.employment_type,
                            seniority_level = excluded.seniority_level,
                            job_type = excluded.job_type,
                            lang_name = excluded.lang_name,
                            min_work_exp_year = excluded.min_work_exp_year,
                            number_of_openings = excluded.number_of_openings,
                            city = excluded.city,
                            district = excluded.district,
                            posted_at = excluded.posted_at,
                            status = CASE WHEN jobs.status IN ('NEW', 'CLOSED') THEN 'ACTIVE' ELSE jobs.status END,
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
                .param("employmentType", normalized.employmentType())
                .param("seniorityLevel", normalized.seniorityLevel())
                .param("jobType", normalized.jobType())
                .param("langName", normalized.langName())
                .param("minWorkExpYear", normalized.minWorkExpYear())
                .param("numberOfOpenings", normalized.numberOfOpenings())
                .param("city", normalized.city())
                .param("district", normalized.district())
                .param("postedAt", normalized.postedAt() == null ? null : Timestamp.from(normalized.postedAt()))
                .param("seenAt", Timestamp.from(seenAt))
                .query(Boolean.class)
                .single();

        return Boolean.TRUE.equals(inserted);
    }
}
