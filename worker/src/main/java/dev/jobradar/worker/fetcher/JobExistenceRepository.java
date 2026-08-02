package dev.jobradar.worker.fetcher;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JobExistenceRepository {

    private final JdbcClient jdbcClient;

    public boolean exists(String source, String sourceJobId) {
        return jdbcClient.sql("SELECT 1 FROM jobs WHERE source = :source AND source_job_id = :sourceJobId")
                .param("source", source)
                .param("sourceJobId", sourceJobId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }
}
