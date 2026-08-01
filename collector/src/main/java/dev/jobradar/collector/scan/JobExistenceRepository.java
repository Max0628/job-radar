package dev.jobradar.collector.scan;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 淺掃早停判斷用（見 architecture.md D6）：只看「這筆職缺存不存在」，不比對內容有沒有變
 * ——內容比對需要完整 detail payload，list 階段對 needsDetail=true 的來源根本拿不到。
 *
 * collector 模組不依賴 worker 模組（各自獨立部署，見 D7），worker 已有同名/同邏輯的
 * repository（worker.fetcher.JobExistenceRepository），這裡是刻意的小型重複，不是共用
 * 一個模組划算的規模。
 */
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
