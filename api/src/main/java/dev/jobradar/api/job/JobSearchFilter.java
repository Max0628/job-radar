package dev.jobradar.api.job;

/**
 * job-browse-api 的篩選條件（見 add-job-dashboard/specs/job-browse-api）。全部欄位可為 null，
 * 代表該維度不篩選。
 */
public record JobSearchFilter(
        String q,
        String district,
        String city,
        Long salaryMin,
        Long salaryMax,
        String jobType,
        String source,
        String status
) {
}
