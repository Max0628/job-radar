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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
// CI 的 Runner 用 Kubernetes executor 跑 job，pod 內沒有 Docker daemon 可用，
// 見 .gitlab-ci.yml 的 -PskipDockerTests
@Tag("requires-docker")
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
        SearchQuery request = new SearchQuery(0, "yourator", "TPE",
                List.of("後端工程", "DevOps / SRE"), 120, true, null);

        SearchQuery created = repository.insert(request);

        assertThat(created.id()).isPositive();
        assertThat(created.categories()).containsExactly("後端工程", "DevOps / SRE");

        Optional<SearchQuery> found = repository.findById(created.id());
        assertThat(found).isPresent();
        assertThat(found.get().categories()).containsExactly("後端工程", "DevOps / SRE");
    }

    @Test
    void insertWithNullCategoriesReadsBackAsEmptyList() {
        // 拿掉 keyword 之後 API 層會擋空 categories（見 SearchQueryController），
        // 但 repository 本身仍應忠實反映 DB 現況，不多做隱性假設
        SearchQuery request = new SearchQuery(0, "cakeresume", "台北市, 台灣",
                null, 120, true, null);

        SearchQuery created = repository.insert(request);

        assertThat(created.categories()).isEmpty();
    }

    @Test
    void updateChangesFields() {
        SearchQuery created = repository.insert(
                new SearchQuery(0, "yourator", "TPE", List.of("後端工程"), 120, true, null));

        Optional<SearchQuery> updated = repository.update(created.id(),
                new SearchQuery(created.id(), "yourator", "NWT",
                        List.of("全端工程", "資料庫"), 60, false, null));

        assertThat(updated).isPresent();
        assertThat(updated.get().location()).isEqualTo("NWT");
        assertThat(updated.get().categories()).containsExactly("全端工程", "資料庫");
        assertThat(updated.get().enabled()).isFalse();
    }

    /**
     * 自動停用（見 add-104-source/design.md「自動關閉」決策）寫入的 disabled_reason，
     * 使用者重新啟用（enabled=true）時要自動清掉，不需要另外操作——直接用 raw SQL
     * 模擬 collector/worker 端的 disableAllForSource 寫入（repository.insert 本身不
     * 接受 disabledReason，新建查詢一律是 null）。
     */
    @Test
    void reEnablingClearsDisabledReasonButManualDisablePreservesNull() {
        SearchQuery created = repository.insert(
                new SearchQuery(0, "104", null, List.of("2007001016"), 120, true, null));
        jdbcClient.sql("UPDATE search_queries SET enabled = FALSE, disabled_reason = :reason WHERE id = :id")
                .param("reason", "104 returned 403 for page 1")
                .param("id", created.id())
                .update();

        Optional<SearchQuery> stillDisabled = repository.findById(created.id());
        assertThat(stillDisabled).isPresent();
        assertThat(stillDisabled.get().enabled()).isFalse();
        assertThat(stillDisabled.get().disabledReason()).isEqualTo("104 returned 403 for page 1");

        Optional<SearchQuery> reEnabled = repository.update(created.id(),
                new SearchQuery(created.id(), "104", null, List.of("2007001016"), 120, true, null));

        assertThat(reEnabled).isPresent();
        assertThat(reEnabled.get().enabled()).isTrue();
        assertThat(reEnabled.get().disabledReason()).isNull();
    }

    @Test
    void updateNonExistentIdReturnsEmpty() {
        Optional<SearchQuery> result = repository.update(999_999L,
                new SearchQuery(999_999L, "yourator", null, null, 1, true, null));

        assertThat(result).isEmpty();
    }

    @Test
    void deleteRemovesRowAndAssociatedCursor() {
        SearchQuery created = repository.insert(
                new SearchQuery(0, "yourator", null, null, 1, true, null));
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
        // keyword 拿掉之後改用 location 當排序區分依據，測試意圖不變：驗證排序跟分頁
        repository.insert(new SearchQuery(0, "yourator", "b", null, 1, true, null));
        repository.insert(new SearchQuery(0, "yourator", "a", null, 1, true, null));
        repository.insert(new SearchQuery(0, "yourator", "c", null, 1, true, null));

        List<SearchQuery> sorted = repository.findAll(0, 10, "location", "ASC");

        assertThat(sorted).extracting(SearchQuery::location).containsExactly("a", "b", "c");
        assertThat(repository.count()).isEqualTo(3);

        List<SearchQuery> page = repository.findAll(0, 2, "location", "ASC");
        assertThat(page).hasSize(2);
    }
}
