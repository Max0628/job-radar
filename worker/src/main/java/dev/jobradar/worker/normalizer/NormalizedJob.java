package dev.jobradar.worker.normalizer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * 各來源 RawPayloadParser 的統一輸出格式。Phase 002 新增的 Dashboard 欄位
 * （employmentType 以下）皆可為 null，容納平台沒有該資訊的情況（見 design.md D7）。
 *
 * {@code @AllArgsConstructor}：`@Value` 隱含的全參數建構子只要類別裡已手寫任何建構子
 * 就會被抑制，這裡手寫了下面三個向後兼容的相容建構子，需要明確加這個註解保留
 * 15 參數建構子。
 */
@Value
@AllArgsConstructor
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class NormalizedJob {
    String title;
    String company;
    Long salaryMin;
    Long salaryMax;
    String salaryCurrency;
    String description;
    String employmentType;
    String seniorityLevel;
    String jobType;
    String langName;
    Integer minWorkExpYear;
    Integer numberOfOpenings;
    String city;
    String district;
    Instant postedAt;

    // 向後兼容：Phase 001 的呼叫端只帶前六個欄位，Phase 002 新增欄位一律預設 null
    public NormalizedJob(String title, String company, Long salaryMin, Long salaryMax,
                          String salaryCurrency, String description) {
        this(title, company, salaryMin, salaryMax, salaryCurrency, description,
                null, null, null, null, null, null, null, null, null);
    }

    // 向後兼容：add-multi-source-cakeresume 階段的呼叫端只帶到 numberOfOpenings，
    // 這次（add-job-dashboard）新增的 city/district 一律預設 null
    public NormalizedJob(String title, String company, Long salaryMin, Long salaryMax,
                          String salaryCurrency, String description, String employmentType,
                          String seniorityLevel, String jobType, String langName,
                          Integer minWorkExpYear, Integer numberOfOpenings) {
        this(title, company, salaryMin, salaryMax, salaryCurrency, description,
                employmentType, seniorityLevel, jobType, langName, minWorkExpYear, numberOfOpenings,
                null, null, null);
    }

    // 向後兼容：add-job-dashboard 階段的呼叫端只帶到 city/district，這次
    // （add-job-posted-date）新增的 postedAt 預設 null
    public NormalizedJob(String title, String company, Long salaryMin, Long salaryMax,
                          String salaryCurrency, String description, String employmentType,
                          String seniorityLevel, String jobType, String langName,
                          Integer minWorkExpYear, Integer numberOfOpenings, String city, String district) {
        this(title, company, salaryMin, salaryMax, salaryCurrency, description,
                employmentType, seniorityLevel, jobType, langName, minWorkExpYear, numberOfOpenings,
                city, district, null);
    }
}
