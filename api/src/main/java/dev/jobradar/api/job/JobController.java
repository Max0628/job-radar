package dev.jobradar.api.job;

import dev.jobradar.common.domain.JobStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * jobs 的唯讀查詢端點，供 Dashboard 職缺瀏覽台使用（見
 * add-job-dashboard/specs/job-browse-api）。不提供編輯/刪除——職缺資料只能由爬蟲管線寫入
 * （見 dashboard-frontend spec 的「唯讀」scenario）。
 */
@RestController
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @GetMapping("/api/jobs")
    public ResponseEntity<List<JobResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Long salaryMin,
            @RequestParam(required = false) Long salaryMax,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) JobStatus status,
            @RequestParam(name = "_start", defaultValue = "0") int start,
            @RequestParam(name = "_end", defaultValue = "20") int end,
            @RequestParam(name = "_sort", defaultValue = "postedAt") String sort,
            @RequestParam(name = "_order", defaultValue = "DESC") String order
    ) {
        JobSearchFilter filter = new JobSearchFilter(q, district, city, salaryMin, salaryMax,
                jobType, source, status);
        List<JobResponse> body = jobService.search(filter, start, end, sort, order);
        long total = jobService.count(filter);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_RANGE, "jobs %d-%d/%d".formatted(start, end, total))
                .header("X-Total-Count", String.valueOf(total))
                .body(body);
    }

    @GetMapping("/api/jobs/{id}")
    public JobResponse getOne(@PathVariable long id) {
        return jobService.findOne(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
