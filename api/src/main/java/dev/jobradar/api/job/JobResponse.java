package dev.jobradar.api.job;

import dev.jobradar.common.domain.Job;
import java.time.Instant;

/**
 * job-browse-api 的回應形狀：Job 的所有欄位 + 收藏狀態（不是 DB 欄位，由
 * FavoriteRepository 查詢後在 controller 層組裝，見 job-favorites spec）。
 * favoriteId 而不是只給布林值——前端要取消收藏時需要這個 id 才能呼叫
 * DELETE /api/favorites/:id（Job 本身沒有這個 id，見 FavoriteRepository 的實作備註）。
 */
public record JobResponse(
        long id,
        String source,
        String sourceJobId,
        String title,
        String company,
        Long salaryMin,
        Long salaryMax,
        String salaryCurrency,
        String url,
        String status,
        String employmentType,
        String seniorityLevel,
        String jobType,
        String langName,
        Integer minWorkExpYear,
        Integer numberOfOpenings,
        String city,
        String district,
        Instant postedAt,
        Instant firstSeenAt,
        Instant lastSeenAt,
        boolean isFavorited,
        Long favoriteId
) {
    public static JobResponse from(Job job, Long favoriteId) {
        return new JobResponse(
                job.id(), job.source(), job.sourceJobId(), job.title(), job.company(),
                job.salaryMin(), job.salaryMax(), job.salaryCurrency(), job.url(), job.status(),
                job.employmentType(), job.seniorityLevel(), job.jobType(), job.langName(),
                job.minWorkExpYear(), job.numberOfOpenings(), job.city(), job.district(),
                job.postedAt(), job.firstSeenAt(), job.lastSeenAt(), favoriteId != null, favoriteId
        );
    }
}
