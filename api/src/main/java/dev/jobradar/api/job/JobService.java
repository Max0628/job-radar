package dev.jobradar.api.job;

import dev.jobradar.api.favorite.FavoriteRepository;
import dev.jobradar.common.domain.Job;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * jobs 查詢的業務邏輯：把 Job 跟收藏狀態組成 JobResponse（見 job-favorites spec）。
 * 從 JobController 拉出來，跟 FacetsController→FacetsService 走同一種三層模式
 * （見 DECIPLINE.md 的架構討論——原本 3 個 Controller 裡只有 FacetsController
 * 有 Service，這裡補齊另外兩個）。
 */
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final FavoriteRepository favoriteRepository;

    public List<JobResponse> search(JobSearchFilter filter, int start, int end, String sort, String order) {
        List<Job> jobs = jobRepository.search(filter, start, end, sort, order);
        Map<String, Long> favoriteIds = favoriteRepository.findFavoriteIdsByPairKeys(
                jobs.stream().map(j -> j.source() + ":" + j.sourceJobId()).toList());

        return jobs.stream()
                .map(j -> JobResponse.from(j, favoriteIds.get(j.source() + ":" + j.sourceJobId())))
                .toList();
    }

    public long count(JobSearchFilter filter) {
        return jobRepository.count(filter);
    }

    public Optional<JobResponse> findOne(long id) {
        return jobRepository.findById(id)
                .map(job -> {
                    Long favoriteId = favoriteRepository.findFavoriteId(job.source(), job.sourceJobId()).orElse(null);
                    return JobResponse.from(job, favoriteId);
                });
    }
}
