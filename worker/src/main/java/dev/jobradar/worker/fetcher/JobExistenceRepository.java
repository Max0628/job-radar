package dev.jobradar.worker.fetcher;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobExistenceRepository {

    private final JdbcClient jdbcClient;

    public JobExistenceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean exists(String source, String sourceJobId) {
        return jdbcClient.sql("SELECT 1 FROM jobs WHERE source = :source AND source_job_id = :sourceJobId")
                .param("source", source)
                .param("sourceJobId", sourceJobId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }
}
