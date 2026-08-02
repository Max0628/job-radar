package dev.jobradar.common.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.jobradar.common.source.Source;
import java.time.Instant;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * jobs 表的一列現況資料。(source, sourceJobId) 是唯一鍵。
 * 供 api 模組的唯讀查詢使用（見 add-job-dashboard/specs/job-browse-api）。
 *
 * {@code @Accessors(fluent = true)}：保留 record 換 Lombok 前的無前綴存取方法（`.source()`
 * 而非 `.getSource()`），避免全專案呼叫端跟著改名——這是這次 record→Lombok 轉換全部
 * 類別的統一慣例，見 DECIPLINE.md 的轉換規則。
 *
 * {@code @JsonAutoDetect(fieldVisibility = ANY)}：fluent 存取方法 Jackson 序列化時看不到
 * （預設只認 getXxx()/isXxx()），這個回應會被 JobController 直接序列化，不加這個會回傳
 * 空物件——實測過 SearchQuery 少加這行時 API 回傳 `{}`，這裡統一補上避免同樣的坑。
 */
@Value
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class Job {
    long id;
    Source source;
    String sourceJobId;
    String title;
    String company;
    Long salaryMin;
    Long salaryMax;
    String salaryCurrency;
    String url;
    String contentHash;
    JobStatus status;
    String employmentType;
    String seniorityLevel;
    String jobType;
    String langName;
    Integer minWorkExpYear;
    Integer numberOfOpenings;
    String city;
    String district;
    Instant postedAt;
    Instant firstSeenAt;
    Instant lastSeenAt;
}
