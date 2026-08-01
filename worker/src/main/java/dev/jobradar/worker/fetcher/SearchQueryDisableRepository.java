package dev.jobradar.worker.fetcher;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * worker 端只需要「疑似被封鎖時自動停用該來源」這一個寫入動作（見
 * add-104-source/design.md「自動關閉」決策），不需要 api/collector 那種完整
 * CRUD/讀取能力，所以獨立成一個窄範圍的 repository，不是共用 api 模組的
 * SearchQueryRepository（worker 對 search_queries 表本來就沒有任何讀取需求）。
 */
@Repository
public class SearchQueryDisableRepository {

    private final JdbcClient jdbcClient;

    public SearchQueryDisableRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

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
}
