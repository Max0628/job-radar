package dev.jobradar.api.job;

import dev.jobradar.common.domain.Job;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * jobs 表的唯讀查詢，供 Dashboard 職缺瀏覽台使用（見
 * add-job-dashboard/specs/job-browse-api）。不提供寫入——職缺資料只能由爬蟲管線寫入。
 */
@Repository
public class JobRepository {

    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "id", "id",
            "title", "title",
            "company", "company",
            "salaryMin", "salary_min",
            "salaryMax", "salary_max",
            "firstSeenAt", "first_seen_at",
            "lastSeenAt", "last_seen_at",
            "postedAt", "posted_at"
    );

    private final JdbcClient jdbcClient;

    public JobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Job> search(JobSearchFilter filter, int start, int end, String sort, String order) {
        WhereClause where = buildWhereClause(filter);
        // 未指定排序時預設用 posted_at（平台真實日期）而不是 last_seen_at（我們的掃描
        // 時間戳），見 add-job-posted-date/design.md
        String column = SORTABLE_COLUMNS.getOrDefault(sort, "posted_at");
        String direction = "ASC".equalsIgnoreCase(order) ? "ASC" : "DESC";
        // DESC 時 PostgreSQL 預設 NULLS FIRST，會把還沒有值的職缺（例如 posted_at 尚未
        // 回填的既有資料）衝到最前面，跟「新的在前」的排序意圖相反，明確覆寫成 NULLS LAST
        String nullsOrder = "DESC".equals(direction) ? "NULLS LAST" : "";
        int limit = Math.max(0, end - start);

        var spec = jdbcClient.sql("""
                        SELECT id, source, source_job_id, title, company, salary_min, salary_max,
                               salary_currency, url, content_hash, status, employment_type,
                               seniority_level, job_type, lang_name, min_work_exp_year,
                               number_of_openings, city, district, posted_at, first_seen_at, last_seen_at
                        FROM jobs
                        %s
                        ORDER BY %s %s %s
                        LIMIT :limit OFFSET :offset
                        """.formatted(where.sql(), column, direction, nullsOrder))
                .param("limit", limit)
                .param("offset", start);
        spec = where.bind(spec);

        return spec.query(this::mapRow).list();
    }

    public long count(JobSearchFilter filter) {
        WhereClause where = buildWhereClause(filter);
        var spec = jdbcClient.sql("SELECT count(*) FROM jobs %s".formatted(where.sql()));
        spec = where.bind(spec);
        return spec.query(Long.class).single();
    }

    public Optional<Job> findById(long id) {
        return jdbcClient.sql("""
                        SELECT id, source, source_job_id, title, company, salary_min, salary_max,
                               salary_currency, url, content_hash, status, employment_type,
                               seniority_level, job_type, lang_name, min_work_exp_year,
                               number_of_openings, city, district, posted_at, first_seen_at, last_seen_at
                        FROM jobs WHERE id = :id
                        """)
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    private WhereClause buildWhereClause(JobSearchFilter filter) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new java.util.HashMap<>();

        if (filter.q() != null && !filter.q().isBlank()) {
            conditions.add("(title ILIKE :q OR company ILIKE :q)");
            params.put("q", "%" + filter.q() + "%");
        }
        if (filter.district() != null && !filter.district().isBlank()) {
            conditions.add("district = :district");
            params.put("district", filter.district());
        }
        if (filter.city() != null && !filter.city().isBlank()) {
            conditions.add("city = :city");
            params.put("city", filter.city());
        }
        if (filter.salaryMin() != null) {
            conditions.add("salary_min >= :salaryMin");
            params.put("salaryMin", filter.salaryMin());
        }
        if (filter.salaryMax() != null) {
            conditions.add("salary_max <= :salaryMax");
            params.put("salaryMax", filter.salaryMax());
        }
        if (filter.jobType() != null && !filter.jobType().isBlank()) {
            conditions.add("job_type = :jobType");
            params.put("jobType", filter.jobType());
        }
        if (filter.source() != null && !filter.source().isBlank()) {
            conditions.add("source = :source");
            params.put("source", filter.source());
        }
        // status 沒帶篩選時，預設排除 CLOSED（見 job-browse-api spec）；
        // 明確帶 status 時尊重使用者指定的值（含 CLOSED，供未來查歷史用）
        if (filter.status() != null && !filter.status().isBlank()) {
            conditions.add("status = :status");
            params.put("status", filter.status());
        } else {
            conditions.add("status != 'CLOSED'");
        }

        String sql = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        return new WhereClause(sql, params);
    }

    private Job mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Job(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("source_job_id"),
                rs.getString("title"),
                rs.getString("company"),
                (Long) rs.getObject("salary_min"),
                (Long) rs.getObject("salary_max"),
                rs.getString("salary_currency"),
                rs.getString("url"),
                rs.getString("content_hash"),
                rs.getString("status"),
                rs.getString("employment_type"),
                rs.getString("seniority_level"),
                rs.getString("job_type"),
                rs.getString("lang_name"),
                (Integer) rs.getObject("min_work_exp_year"),
                (Integer) rs.getObject("number_of_openings"),
                rs.getString("city"),
                rs.getString("district"),
                rs.getTimestamp("posted_at") == null ? null : rs.getTimestamp("posted_at").toInstant(),
                rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant()
        );
    }

    private record WhereClause(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                spec = spec.param(entry.getKey(), entry.getValue());
            }
            return spec;
        }
    }
}
