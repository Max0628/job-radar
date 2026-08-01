package dev.jobradar.collector.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.domain.SearchQuery;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SearchQueryRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public SearchQueryRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public List<SearchQuery> findAllEnabled() {
        return jdbcClient.sql("""
                        SELECT id, source, location, categories, interval_minutes, enabled
                        FROM search_queries
                        WHERE enabled = TRUE
                        """)
                .query((rs, rowNum) -> new SearchQuery(
                        rs.getLong("id"),
                        rs.getString("source"),
                        rs.getString("location"),
                        parseCategories(rs.getString("categories")),
                        rs.getInt("interval_minutes"),
                        rs.getBoolean("enabled"),
                        null
                ))
                .list();
    }

    /**
     * 疑似被來源網站封鎖時自動停用該來源所有查詢（目前只有 104 會呼叫，見
     * add-104-source/design.md「自動關閉」決策）——Cloudflare 風控是整個網域層級的判定，
     * 不是針對單一查詢，所以是關閉整個 source，不是只關觸發這次失敗的那一筆。
     * 重新啟用是純手動（把 enabled 存回 true，見 api 模組的 update()），這裡不提供
     * 自動恢復。
     */
    public void disableAllForSource(String source, String reason) {
        jdbcClient.sql("""
                        UPDATE search_queries
                        SET enabled = FALSE, disabled_reason = :reason
                        WHERE source = :source
                        """)
                .param("source", source)
                .param("reason", reason)
                .update();
    }

    private List<String> parseCategories(String categoriesJson) {
        if (categoriesJson == null || categoriesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(categoriesJson, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid categories JSON for search_queries: " + categoriesJson, e);
        }
    }
}
