package dev.jobradar.common.domain;

import java.time.Instant;

/**
 * jobs 表的一列現況資料。(source, sourceJobId) 是唯一鍵。
 * 供 api 模組的唯讀查詢使用（見 add-job-dashboard/specs/job-browse-api）。
 */
public record Job(
        long id,
        String source,
        String sourceJobId,
        String title,
        String company,
        Long salaryMin,
        Long salaryMax,
        String salaryCurrency,
        String url,
        String contentHash,
        String status,
        String employmentType,
        String seniorityLevel,
        String jobType,
        String langName,
        Integer minWorkExpYear,
        Integer numberOfOpenings,
        String city,
        String district,
        Instant firstSeenAt,
        Instant lastSeenAt
) {
}
