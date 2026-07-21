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
                        SELECT id, source, keyword, location, categories, max_pages, interval_minutes, enabled
                        FROM search_queries
                        WHERE enabled = TRUE
                        """)
                .query((rs, rowNum) -> new SearchQuery(
                        rs.getLong("id"),
                        rs.getString("source"),
                        rs.getString("keyword"),
                        rs.getString("location"),
                        parseCategories(rs.getString("categories")),
                        rs.getInt("max_pages"),
                        rs.getInt("interval_minutes"),
                        rs.getBoolean("enabled")
                ))
                .list();
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
