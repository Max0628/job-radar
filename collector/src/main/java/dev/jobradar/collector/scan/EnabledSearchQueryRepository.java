package dev.jobradar.collector.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.domain.SearchQuery;
import dev.jobradar.common.source.Source;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * search_queries 的窄範圍唯讀版本，只給 {@code ScanScheduler}/{@code ScanService} 用
 * （只讀 enabled=TRUE 的列 + 一個停用寫入動作）。改名跟 {@code api.searchquery.SearchQueryRepository}
 * 那份完整 CRUD 版本區隔開，避免兩個同名類別在 IDE 全域搜尋時混淆——兩者刻意不共用
 * （見 architecture.md D7：collector 不依賴 worker/api，讀寫語意也不同）。
 */
@Repository
@RequiredArgsConstructor
public class EnabledSearchQueryRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public List<SearchQuery> findAllEnabled() {
        return jdbcClient.sql("""
                        SELECT id, source, location, categories, interval_minutes, enabled
                        FROM search_queries
                        WHERE enabled = TRUE
                        """)
                .query((rs, rowNum) -> new SearchQuery(
                        rs.getLong("id"),
                        Source.fromValue(rs.getString("source")),
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
    public void disableAllForSource(Source source, String reason) {
        jdbcClient.sql("""
                        UPDATE search_queries
                        SET enabled = FALSE, disabled_reason = :reason
                        WHERE source = :source
                        """)
                .param("source", source.value())
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
