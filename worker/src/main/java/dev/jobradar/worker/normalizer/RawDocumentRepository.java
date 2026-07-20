package dev.jobradar.worker.normalizer;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RawDocumentRepository {

    private final JdbcClient jdbcClient;

    public RawDocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertIgnore(String source, String sourceJobId, Instant fetchedAt, String payloadJson) {
        jdbcClient.sql("""
                        INSERT INTO raw_documents (source, source_job_id, fetched_at, payload)
                        VALUES (:source, :sourceJobId, :fetchedAt, :payload)
                        ON CONFLICT (source, source_job_id, fetched_at) DO NOTHING
                        """)
                .param("source", source)
                .param("sourceJobId", sourceJobId)
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .param("payload", PgJson.jsonb(payloadJson))
                .update();
    }
}
