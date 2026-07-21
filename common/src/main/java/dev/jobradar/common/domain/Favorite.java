package dev.jobradar.common.domain;

import java.time.Instant;

/**
 * favorites 表的一列。單使用者，不需要 user_id（見 add-job-dashboard/design.md D6）。
 * (source, sourceJobId) 是唯一鍵。
 */
public record Favorite(
        long id,
        String source,
        String sourceJobId,
        Instant createdAt
) {
}
