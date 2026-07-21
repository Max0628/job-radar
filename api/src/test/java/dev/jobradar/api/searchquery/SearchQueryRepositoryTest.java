package dev.jobradar.api.searchquery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import dev.jobradar.common.domain.SearchQuery;
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
class SearchQueryRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("docker.io/library/postgres:16-alpine").asCompatibleSubstituteFor("postgres"));

    static HikariDataSource dataSource;
    static JdbcClient jdbcClient;
    SearchQueryRepository repository;

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
        jdbcClient.sql("DELETE FROM scrape_cursors").update();
        jdbcClient.sql("DELETE FROM search_queries").update();
        repository = new SearchQueryRepository(jdbcClient, new ObjectMapper());
    }

    @Test
    void insertsAndReadsBackWithCategories() {
        SearchQuery request = new SearchQuery(0, "yourator", "", "TPE",
                List.of("後端工程", "DevOps / SRE"), 10, 120, true);

        SearchQuery created = repository.insert(request);

        assertThat(created.id()).isPositive();
        assertThat(created.categories()).containsExactly("後端工程", "DevOps / SRE");

        Optional<SearchQuery> found = repository.findById(created.id());
        assertThat(found).isPresent();
        assertThat(found.get().categories()).containsExactly("後端工程", "DevOps / SRE");
    }

    @Test
    void insertWithNullCategoriesReadsBackAsEmptyList() {
        SearchQuery request = new SearchQuery(0, "cakeresume", "devops", "台北市, 台灣",
                null, 10, 120, true);

        SearchQuery created = repository.insert(request);

        assertThat(created.categories()).isEmpty();
    }

    @Test
    void updateChangesFields() {
        SearchQuery created = repository.insert(
                new SearchQuery(0, "yourator", "old", "TPE", List.of("後端工程"), 10, 120, true));

        Optional<SearchQuery> updated = repository.update(created.id(),
                new SearchQuery(created.id(), "yourator", "new", "NWT",
                        List.of("全端工程", "資料庫"), 5, 60, false));

        assertThat(updated).isPresent();
        assertThat(updated.get().keyword()).isEqualTo("new");
        assertThat(updated.get().location()).isEqualTo("NWT");
        assertThat(updated.get().categories()).containsExactly("全端工程", "資料庫");
        assertThat(updated.get().maxPages()).isEqualTo(5);
        assertThat(updated.get().enabled()).isFalse();
    }

    @Test
    void updateNonExistentIdReturnsEmpty() {
        Optional<SearchQuery> result = repository.update(999_999L,
                new SearchQuery(999_999L, "yourator", "x", null, null, 1, 1, true));

        assertThat(result).isEmpty();
    }

    @Test
    void deleteRemovesRowAndAssociatedCursor() {
        SearchQuery created = repository.insert(
                new SearchQuery(0, "yourator", "x", null, null, 1, 1, true));
        jdbcClient.sql("INSERT INTO scrape_cursors (search_query_id) VALUES (:id)")
                .param("id", created.id())
                .update();

        boolean deleted = repository.delete(created.id());

        assertThat(deleted).isTrue();
        assertThat(repository.findById(created.id())).isEmpty();
        Long cursorCount = jdbcClient.sql("SELECT count(*) FROM scrape_cursors WHERE search_query_id = :id")
                .param("id", created.id())
                .query(Long.class)
                .single();
        assertThat(cursorCount).isZero();
    }

    @Test
    void deleteNonExistentIdReturnsFalse() {
        assertThat(repository.delete(999_999L)).isFalse();
    }

    @Test
    void findAllRespectsSortAndPagination() {
        repository.insert(new SearchQuery(0, "yourator", "b", null, null, 1, 1, true));
        repository.insert(new SearchQuery(0, "yourator", "a", null, null, 1, 1, true));
        repository.insert(new SearchQuery(0, "yourator", "c", null, null, 1, 1, true));

        List<SearchQuery> sorted = repository.findAll(0, 10, "keyword", "ASC");

        assertThat(sorted).extracting(SearchQuery::keyword).containsExactly("a", "b", "c");
        assertThat(repository.count()).isEqualTo(3);

        List<SearchQuery> page = repository.findAll(0, 2, "keyword", "ASC");
        assertThat(page).hasSize(2);
    }
}
