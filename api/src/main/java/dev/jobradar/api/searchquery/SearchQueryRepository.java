package dev.jobradar.api.searchquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.db.PgJson;
import dev.jobradar.common.domain.SearchQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * search_queries 表的完整 CRUD（見 add-job-dashboard/specs/search-query-management-api）。
 * 與 collector 模組的 SearchQueryRepository 不同——collector 那份只讀 enabled=TRUE 的列
 * （給 Scheduler 用），這份是 api 模組專用的完整讀寫版本，兩者刻意不共用，因為讀寫語意不同。
 */
@Repository
public class SearchQueryRepository {

    // 白名單排序欄位，避免把使用者輸入直接接進 SQL ORDER BY 造成注入風險
    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "id", "id",
            "source", "source",
            "location", "location",
            "intervalMinutes", "interval_minutes",
            "enabled", "enabled"
    );

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public SearchQueryRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public List<SearchQuery> findAll(int start, int end, String sort, String order) {
        String column = SORTABLE_COLUMNS.getOrDefault(sort, "id");
        String direction = "DESC".equalsIgnoreCase(order) ? "DESC" : "ASC";
        int limit = Math.max(0, end - start);

        return jdbcClient.sql("""
                        SELECT id, source, location, categories, interval_minutes, enabled, disabled_reason
                        FROM search_queries
                        ORDER BY %s %s
                        LIMIT :limit OFFSET :offset
                        """.formatted(column, direction))
                .param("limit", limit)
                .param("offset", start)
                .query(this::mapRow)
                .list();
    }

    public long count() {
        return jdbcClient.sql("SELECT count(*) FROM search_queries")
                .query(Long.class)
                .single();
    }

    public Optional<SearchQuery> findById(long id) {
        return jdbcClient.sql("""
                        SELECT id, source, location, categories, interval_minutes, enabled, disabled_reason
                        FROM search_queries WHERE id = :id
                        """)
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    public SearchQuery insert(SearchQuery query) {
        Long id = jdbcClient.sql("""
                        INSERT INTO search_queries (source, location, categories,
                                                     interval_minutes, enabled)
                        VALUES (:source, :location, :categories, :intervalMinutes, :enabled)
                        RETURNING id
                        """)
                .param("source", query.source())
                .param("location", query.location())
                .param("categories", toJsonb(query.categories()))
                .param("intervalMinutes", query.intervalMinutes())
                .param("enabled", query.enabled())
                .query(Long.class)
                .single();

        return findById(id).orElseThrow();
    }

    /**
     * 重新啟用（enabled=true）時自動清掉 disabled_reason——這個欄位只有系統自動停用時
     * 才會寫入（見 add-104-source/design.md「自動關閉」決策，目前只有 104 blocked 偵測
     * 會寫），一旦使用者手動打開開關，代表舊的停用原因已經不成立，不需要使用者另外清除。
     * 手動停用（enabled=false）時保留原值不動——本來就是 null（使用者自己關的，不是
     * 系統自動關的）。
     */
    public Optional<SearchQuery> update(long id, SearchQuery query) {
        String disabledReason = query.enabled() ? null : query.disabledReason();
        int updated = jdbcClient.sql("""
                        UPDATE search_queries
                        SET source = :source, location = :location,
                            categories = :categories,
                            interval_minutes = :intervalMinutes, enabled = :enabled,
                            disabled_reason = :disabledReason
                        WHERE id = :id
                        """)
                .param("source", query.source())
                .param("location", query.location())
                .param("categories", toJsonb(query.categories()))
                .param("intervalMinutes", query.intervalMinutes())
                .param("enabled", query.enabled())
                .param("disabledReason", disabledReason)
                .param("id", id)
                .update();

        return updated == 0 ? Optional.empty() : findById(id);
    }

    /**
     * 刪除時一併清掉對應的 scrape_cursors（有 FK 參照，不先刪會違反約束），
     * 見 search-query-management-api spec 的「刪除爬蟲設定」scenario。
     */
    public boolean delete(long id) {
        jdbcClient.sql("DELETE FROM scrape_cursors WHERE search_query_id = :id")
                .param("id", id)
                .update();
        int deleted = jdbcClient.sql("DELETE FROM search_queries WHERE id = :id")
                .param("id", id)
                .update();
        return deleted > 0;
    }

    private SearchQuery mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SearchQuery(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("location"),
                parseCategories(rs.getString("categories")),
                rs.getInt("interval_minutes"),
                rs.getBoolean("enabled"),
                rs.getString("disabled_reason")
        );
    }

    private List<String> parseCategories(String categoriesJson) {
        if (categoriesJson == null || categoriesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(categoriesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid categories JSON for search_queries: " + categoriesJson, e);
        }
    }

    private Object toJsonb(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return null;
        }
        try {
            return PgJson.jsonb(objectMapper.writeValueAsString(categories));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize categories to JSON", e);
        }
    }

    public static Set<String> registeredSources() {
        return Set.of("yourator", "cakeresume", "104");
    }
}
