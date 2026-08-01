package dev.jobradar.collector.scan;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScrapeCursorRepository {

    private final JdbcClient jdbcClient;

    public ScrapeCursorRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Instant> findLastScannedAt(long searchQueryId) {
        return jdbcClient.sql("SELECT last_scanned_at FROM scrape_cursors WHERE search_query_id = :searchQueryId")
                .param("searchQueryId", searchQueryId)
                .query(Instant.class)
                .optional();
    }

    /**
     * 深掃接續用（見 architecture.md D6）。空值代表沒有進行中的深掃，該從第 1 頁開始。
     */
    public Optional<Integer> findLastPageScanned(long searchQueryId) {
        return jdbcClient.sql("SELECT last_page_scanned FROM scrape_cursors WHERE search_query_id = :searchQueryId")
                .param("searchQueryId", searchQueryId)
                .query(Integer.class)
                .optional();
    }

    public Optional<Instant> findLastDeepScanCompletedAt(long searchQueryId) {
        return jdbcClient.sql(
                        "SELECT last_deep_scan_completed_at FROM scrape_cursors WHERE search_query_id = :searchQueryId")
                .param("searchQueryId", searchQueryId)
                .query(Instant.class)
                .optional();
    }

    /**
     * 淺掃完全不碰 last_page_scanned/last_deep_scan_completed_at——這兩個欄位是深掃進度的
     * 專屬狀態，淺掃寫入會污染深掃下次該接續到哪一頁（見 architecture.md D6）。
     */
    public void updateAfterLightScan(long searchQueryId, Instant scannedAt) {
        jdbcClient.sql("UPDATE scrape_cursors SET last_scanned_at = :scannedAt WHERE search_query_id = :searchQueryId")
                .param("scannedAt", Timestamp.from(scannedAt))
                .param("searchQueryId", searchQueryId)
                .update();
    }

    /**
     * reachedEnd=true（真的翻完，或分頁卡住安全網觸發）：接續頁碼歸零、記錄深掃完成時間。
     * reachedEnd=false（被時間預算打斷）：接續頁碼存下一輪該從哪一頁開始，深掃完成時間
     * 不更新——代表這次深掃還沒真的做完，不是重新起算一輪新的間隔。
     */
    public void updateAfterDeepScan(long searchQueryId, Instant scannedAt, boolean reachedEnd, int nextPageToResume) {
        if (reachedEnd) {
            jdbcClient.sql("""
                            UPDATE scrape_cursors
                            SET last_scanned_at = :scannedAt, last_page_scanned = NULL,
                                last_deep_scan_completed_at = :scannedAt
                            WHERE search_query_id = :searchQueryId
                            """)
                    .param("scannedAt", Timestamp.from(scannedAt))
                    .param("searchQueryId", searchQueryId)
                    .update();
        } else {
            jdbcClient.sql("""
                            UPDATE scrape_cursors
                            SET last_scanned_at = :scannedAt, last_page_scanned = :nextPageToResume
                            WHERE search_query_id = :searchQueryId
                            """)
                    .param("scannedAt", Timestamp.from(scannedAt))
                    .param("nextPageToResume", nextPageToResume)
                    .param("searchQueryId", searchQueryId)
                    .update();
        }
    }
}
