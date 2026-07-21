package dev.jobradar.api.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.jobradar.common.domain.Job;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JobRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("docker.io/library/postgres:16-alpine").asCompatibleSubstituteFor("postgres"));

    static HikariDataSource dataSource;
    static JdbcClient jdbcClient;
    JobRepository repository;

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

    @BeforeEach
    void cleanTable() {
        jdbcClient.sql("DELETE FROM jobs").update();
        repository = new JobRepository(jdbcClient);
    }

    private void insertJob(String source, String sourceJobId, String title, String company,
                            Long salaryMin, Long salaryMax, String jobType, String city,
                            String district, String status) {
        jdbcClient.sql("""
                        INSERT INTO jobs (source, source_job_id, title, company, salary_min, salary_max,
                                          url, content_hash, status, job_type, city, district,
                                          first_seen_at, last_seen_at)
                        VALUES (:source, :sourceJobId, :title, :company, :salaryMin, :salaryMax,
                                :url, :contentHash, :status, :jobType, :city, :district, :seenAt, :seenAt)
                        """)
                .param("source", source)
                .param("sourceJobId", sourceJobId)
                .param("title", title)
                .param("company", company)
                .param("salaryMin", salaryMin)
                .param("salaryMax", salaryMax)
                .param("url", "https://example.com/" + sourceJobId)
                .param("contentHash", "hash-" + sourceJobId)
                .param("status", status)
                .param("jobType", jobType)
                .param("city", city)
                .param("district", district)
                .param("seenAt", Timestamp.from(Instant.now()))
                .update();
    }

    @Test
    void searchByKeywordMatchesTitleOrCompany() {
        insertJob("yourator", "1", "Backend Engineer", "Acme", 1_000_000L, 1_500_000L,
                "full_time", "台北市", "信義區", "ACTIVE");
        insertJob("yourator", "2", "Frontend Engineer", "BackendCorp", 900_000L, 1_200_000L,
                "full_time", "台北市", "大安區", "ACTIVE");
        insertJob("yourator", "3", "Sales Manager", "Acme", 800_000L, 1_000_000L,
                "full_time", "台北市", null, "ACTIVE");

        List<Job> results = repository.search(
                new JobSearchFilter("backend", null, null, null, null, null, null, null),
                0, 20, "id", "ASC");

        assertThat(results).extracting(Job::title)
                .containsExactlyInAnyOrder("Backend Engineer", "Frontend Engineer");
    }

    @Test
    void searchByDistrictFiltersExactMatch() {
        insertJob("yourator", "1", "Job A", "Acme", null, null, null, "台北市", "信義區", "ACTIVE");
        insertJob("yourator", "2", "Job B", "Acme", null, null, null, "台北市", "大安區", "ACTIVE");

        List<Job> results = repository.search(
                new JobSearchFilter(null, "信義區", null, null, null, null, null, null),
                0, 20, "id", "ASC");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Job A");
    }

    @Test
    void searchBySalaryRange() {
        insertJob("yourator", "1", "Low Pay", "Acme", 500_000L, 700_000L, null, null, null, "ACTIVE");
        insertJob("yourator", "2", "Mid Pay", "Acme", 1_000_000L, 1_500_000L, null, null, null, "ACTIVE");
        insertJob("yourator", "3", "High Pay", "Acme", 2_000_000L, 3_000_000L, null, null, null, "ACTIVE");

        List<Job> results = repository.search(
                new JobSearchFilter(null, null, null, 900_000L, 2_000_000L, null, null, null),
                0, 20, "id", "ASC");

        assertThat(results).extracting(Job::title).containsExactly("Mid Pay");
    }

    @Test
    void searchExcludesClosedByDefault() {
        insertJob("yourator", "1", "Active Job", "Acme", null, null, null, null, null, "ACTIVE");
        insertJob("yourator", "2", "Closed Job", "Acme", null, null, null, null, null, "CLOSED");

        List<Job> results = repository.search(
                new JobSearchFilter(null, null, null, null, null, null, null, null),
                0, 20, "id", "ASC");

        assertThat(results).extracting(Job::title).containsExactly("Active Job");
    }

    @Test
    void searchWithExplicitStatusIncludesClosed() {
        insertJob("yourator", "1", "Closed Job", "Acme", null, null, null, null, null, "CLOSED");

        List<Job> results = repository.search(
                new JobSearchFilter(null, null, null, null, null, null, null, "CLOSED"),
                0, 20, "id", "ASC");

        assertThat(results).extracting(Job::title).containsExactly("Closed Job");
    }

    @Test
    void countMatchesFilteredResultSize() {
        insertJob("yourator", "1", "Job A", "Acme", null, null, null, null, null, "ACTIVE");
        insertJob("cakeresume", "2", "Job B", "Acme", null, null, null, null, null, "ACTIVE");

        long count = repository.count(
                new JobSearchFilter(null, null, null, null, null, null, "yourator", null));

        assertThat(count).isEqualTo(1);
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        Optional<Job> result = repository.findById(999_999L);

        assertThat(result).isEmpty();
    }
}
