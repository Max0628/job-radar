package dev.jobradar.api.favorite;

import dev.jobradar.common.domain.Favorite;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * favorites 表的存取（見 add-job-dashboard/specs/job-favorites）。單使用者，不需要 user_id。
 */
@Repository
public class FavoriteRepository {

    private final JdbcClient jdbcClient;

    public FavoriteRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Favorite> findAll() {
        return jdbcClient.sql("""
                        SELECT id, source, source_job_id, created_at FROM favorites
                        ORDER BY created_at DESC
                        """)
                .query(this::mapRow)
                .list();
    }

    /**
     * 收藏冪等：重複收藏同一筆（相同 source+sourceJobId）不報錯，回傳既有記錄
     * （見 job-favorites spec「成功收藏」scenario）。ON CONFLICT DO UPDATE 一步到位，
     * 不用先查再寫（避免不必要的 race window）。
     */
    public Favorite insertIfAbsent(String source, String sourceJobId) {
        Long id = jdbcClient.sql("""
                        INSERT INTO favorites (source, source_job_id)
                        VALUES (:source, :sourceJobId)
                        ON CONFLICT (source, source_job_id) DO UPDATE SET source = excluded.source
                        RETURNING id
                        """)
                .param("source", source)
                .param("sourceJobId", sourceJobId)
                .query(Long.class)
                .single();
        return findById(id).orElseThrow();
    }

    public boolean delete(long id) {
        int deleted = jdbcClient.sql("DELETE FROM favorites WHERE id = :id")
                .param("id", id)
                .update();
        return deleted > 0;
    }

    /**
     * 批次查詢一批 "source:sourceJobId" key 對應的 favorite id，供職缺列表標記
     * isFavorited/favoriteId 用，避免每筆職缺各查一次 DB（見 job-favorites spec
     * 「職缺查詢結果標記收藏狀態」）。回傳 id 而不是只回布林值，因為前端要取消收藏時
     * 需要 favorite 自己的 id 才能呼叫 DELETE /api/favorites/:id（Job 本身沒有這個 id）。
     * 用字串串接的 pair key 配合標準 IN (:list) 展開，避免 source IN (:sources)
     * AND sourceJobId IN (:ids) 這種各自獨立比對造成的跨組合誤判
     * （例如 A 平台的 id 恰好等於 B 平台的另一筆 id 時會誤標記）。
     */
    public Map<String, Long> findFavoriteIdsByPairKeys(List<String> pairKeys) {
        if (pairKeys.isEmpty()) {
            return Map.of();
        }
        List<Favorite> matches = jdbcClient.sql("""
                        SELECT id, source, source_job_id, created_at FROM favorites
                        WHERE (source || ':' || source_job_id) IN (:pairKeys)
                        """)
                .param("pairKeys", pairKeys)
                .query(this::mapRow)
                .list();

        Map<String, Long> result = new HashMap<>();
        for (Favorite favorite : matches) {
            result.put(favorite.source() + ":" + favorite.sourceJobId(), favorite.id());
        }
        return result;
    }

    public Optional<Long> findFavoriteId(String source, String sourceJobId) {
        return findBySourceAndSourceJobId(source, sourceJobId).map(Favorite::id);
    }

    private Optional<Favorite> findBySourceAndSourceJobId(String source, String sourceJobId) {
        return jdbcClient.sql("""
                        SELECT id, source, source_job_id, created_at FROM favorites
                        WHERE source = :source AND source_job_id = :sourceJobId
                        """)
                .param("source", source)
                .param("sourceJobId", sourceJobId)
                .query(this::mapRow)
                .optional();
    }

    private Optional<Favorite> findById(long id) {
        return jdbcClient.sql("SELECT id, source, source_job_id, created_at FROM favorites WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    private Favorite mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Favorite(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("source_job_id"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
