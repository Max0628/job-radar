package dev.jobradar.worker.normalizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 驗證 architecture.md D5 的核心承諾：重複處理同一筆資料不會產生第二筆列或第二次事件觸發依據。
 * 對應 specs/scraping-pipeline/spec.md 的「訊息重放安全」scenario。
 */
@Testcontainers
class NormalizerRepositoriesIdempotencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("docker.io/library/postgres:16-alpine").asCompatibleSubstituteFor("postgres"));

    static HikariDataSource dataSource;
    static JdbcClient jdbcClient;

    @BeforeAll
    static void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcClient = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    @Test
    void upsertingSameJobTwiceOnlyInsertsOnce() {
        JobRepository jobRepository = new JobRepository(jdbcClient);
        NormalizedJob normalized = new NormalizedJob("Backend Engineer", "Acme", 1_000_000L, 1_400_000L, "TWD", "desc");
        Instant seenAt = Instant.now();

        boolean firstInserted = jobRepository.upsert("test-source", "job-1", "https://example.com/1",
                normalized, ContentHash.of(normalized), "{}", seenAt);
        boolean secondInserted = jobRepository.upsert("test-source", "job-1", "https://example.com/1",
                normalized, ContentHash.of(normalized), "{}", seenAt.plusSeconds(10));

        assertThat(firstInserted).isTrue();
        assertThat(secondInserted).isFalse();

        Long count = jdbcClient.sql("SELECT count(*) FROM jobs WHERE source = 'test-source' AND source_job_id = 'job-1'")
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void insertingSameSnapshotTwiceIsIgnored() {
        JobSnapshotRepository snapshotRepository = new JobSnapshotRepository(jdbcClient);
        NormalizedJob normalized = new NormalizedJob("Title", "Company", 100L, 200L, "TWD", "desc");
        Instant scrapedAt = Instant.now();

        snapshotRepository.insertIgnore("test-source", "job-snapshot-1", scrapedAt, normalized, ContentHash.of(normalized));
        snapshotRepository.insertIgnore("test-source", "job-snapshot-1", scrapedAt, normalized, ContentHash.of(normalized));

        Long count = jdbcClient.sql("""
                        SELECT count(*) FROM job_snapshots
                        WHERE source = 'test-source' AND source_job_id = 'job-snapshot-1' AND scraped_at = :scrapedAt
                        """)
                .param("scrapedAt", Timestamp.from(scrapedAt))
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void insertingSameRawDocumentTwiceIsIgnored() {
        RawDocumentRepository rawDocumentRepository = new RawDocumentRepository(jdbcClient);
        Instant fetchedAt = Instant.now();

        rawDocumentRepository.insertIgnore("test-source", "job-raw-1", fetchedAt, "{\"a\":1}");
        rawDocumentRepository.insertIgnore("test-source", "job-raw-1", fetchedAt, "{\"a\":1}");

        Long count = jdbcClient.sql("""
                        SELECT count(*) FROM raw_documents
                        WHERE source = 'test-source' AND source_job_id = 'job-raw-1' AND fetched_at = :fetchedAt
                        """)
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1L);
    }
}
