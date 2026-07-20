package dev.jobradar.worker.normalizer;

public record NormalizedJob(
        String title,
        String company,
        Long salaryMin,
        Long salaryMax,
        String salaryCurrency,
        String description
) {
}
