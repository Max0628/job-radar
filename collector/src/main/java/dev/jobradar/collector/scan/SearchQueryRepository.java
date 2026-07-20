package dev.jobradar.collector.scan;

import dev.jobradar.common.domain.SearchQuery;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SearchQueryRepository {

    private final JdbcClient jdbcClient;

    public SearchQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SearchQuery> findAllEnabled() {
        return jdbcClient.sql("""
                        SELECT id, source, keyword, max_pages, interval_minutes, enabled
                        FROM search_queries
                        WHERE enabled = TRUE
                        """)
                .query((rs, rowNum) -> new SearchQuery(
                        rs.getLong("id"),
                        rs.getString("source"),
                        rs.getString("keyword"),
                        rs.getInt("max_pages"),
                        rs.getInt("interval_minutes"),
                        rs.getBoolean("enabled")
                ))
                .list();
    }
}
