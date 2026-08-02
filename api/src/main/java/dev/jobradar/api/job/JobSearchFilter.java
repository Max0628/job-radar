package dev.jobradar.api.job;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.jobradar.common.domain.JobStatus;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * job-browse-api 的篩選條件（見 add-job-dashboard/specs/job-browse-api）。全部欄位可為 null，
 * 代表該維度不篩選。
 */
@Value
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class JobSearchFilter {
    String q;
    String district;
    String city;
    Long salaryMin;
    Long salaryMax;
    String jobType;
    String source;
    JobStatus status;
}
