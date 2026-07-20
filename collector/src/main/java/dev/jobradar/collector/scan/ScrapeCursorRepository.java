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

    public void updateAfterScan(long searchQueryId, Instant scannedAt, int lastPageScanned) {
        jdbcClient.sql("""
                        UPDATE scrape_cursors
                        SET last_scanned_at = :scannedAt, last_page_scanned = :lastPageScanned
                        WHERE search_query_id = :searchQueryId
                        """)
                .param("scannedAt", Timestamp.from(scannedAt))
                .param("lastPageScanned", lastPageScanned)
                .param("searchQueryId", searchQueryId)
                .update();
    }
}
