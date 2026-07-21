package dev.jobradar.api.favorite;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.jobradar.common.domain.Favorite;
import java.util.List;
import java.util.Map;
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
class FavoriteRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("docker.io/library/postgres:16-alpine").asCompatibleSubstituteFor("postgres"));

    static HikariDataSource dataSource;
    static JdbcClient jdbcClient;
    FavoriteRepository repository;

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
        jdbcClient.sql("DELETE FROM favorites").update();
        repository = new FavoriteRepository(jdbcClient);
    }

    @Test
    void insertIfAbsentCreatesNewRecord() {
        Favorite favorite = repository.insertIfAbsent("yourator", "41246");

        assertThat(favorite.id()).isPositive();
        assertThat(favorite.source()).isEqualTo("yourator");
        assertThat(favorite.sourceJobId()).isEqualTo("41246");
    }

    @Test
    void insertIfAbsentIsIdempotent() {
        Favorite first = repository.insertIfAbsent("yourator", "41246");
        Favorite second = repository.insertIfAbsent("yourator", "41246");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void deleteRemovesRecord() {
        Favorite favorite = repository.insertIfAbsent("yourator", "41246");

        boolean deleted = repository.delete(favorite.id());

        assertThat(deleted).isTrue();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void deleteNonExistentIdReturnsFalse() {
        assertThat(repository.delete(999_999L)).isFalse();
    }

    @Test
    void findFavoriteIdsByPairKeysReturnsOnlyMatchingPairs() {
        Favorite target = repository.insertIfAbsent("yourator", "41246");
        repository.insertIfAbsent("cakeresume", "backend-engineer-1");
        // 故意製造一個「跨組合」情境：cakeresume 平台剛好有個 id 跟 yourator 的 sourceJobId
        // 撞名，驗證 pair-key 比對不會誤判成收藏（見 FavoriteRepository 的實作備註）
        repository.insertIfAbsent("cakeresume", "41246");

        Map<String, Long> result = repository.findFavoriteIdsByPairKeys(
                List.of("yourator:41246", "yourator:99999"));

        assertThat(result).containsExactly(Map.entry("yourator:41246", target.id()));
    }

    @Test
    void findFavoriteIdsByPairKeysWithEmptyInputReturnsEmptyMap() {
        assertThat(repository.findFavoriteIdsByPairKeys(List.of())).isEmpty();
    }

    @Test
    void findFavoriteIdReflectsCurrentState() {
        assertThat(repository.findFavoriteId("yourator", "41246")).isEmpty();

        Favorite created = repository.insertIfAbsent("yourator", "41246");

        assertThat(repository.findFavoriteId("yourator", "41246")).contains(created.id());
    }
}
