package dev.jobradar.common.domain;

import java.time.Instant;

/**
 * jobs 表的一列現況資料。(source, sourceJobId) 是唯一鍵。
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
        Instant firstSeenAt,
        Instant lastSeenAt
) {
}
