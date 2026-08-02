package dev.jobradar.common.repository;

import dev.jobradar.common.source.Source;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 「這筆職缺存不存在」的單純查詢，collector（淺掃早停判斷，見 architecture.md D6）跟
 * worker（detail fetch 前跳過已知職缺，見 DetailFetcherListener）兩邊的查詢邏輯完全相同
 * （同一句 SQL），原本各自複製一份，現在收斂成 common 共用一份，避免兩份程式碼各自維護、
 * 之後改動漏改一邊。
 *
 * 放進 common 是因為這不會造成 collector/worker 互相依賴（D7 真正要避免的是這種橫向依賴）
 * ——兩邊各自獨立部署，只是都依賴同一個下游共用模組，跟 domain model（Job/SearchQuery）
 * 放在 common 是同一個道理。這也是全專案唯一一個「query 邏輯逐字重複」的案例，才值得抽出來；
 * 同名的 SearchQueryRepository/JobRepository（collector/worker/api 各自有）內容其實不同
 * （讀寫範圍不一樣），刻意不共用。
 */
@Repository
@RequiredArgsConstructor
public class JobExistenceRepository {

    private final JdbcClient jdbcClient;

    public boolean exists(Source source, String sourceJobId) {
        return jdbcClient.sql("SELECT 1 FROM jobs WHERE source = :source AND source_job_id = :sourceJobId")
                .param("source", source.value())
                .param("sourceJobId", sourceJobId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }
}
