package dev.jobradar.worker.normalizer;

/**
 * 各來源 RawPayloadParser 的統一輸出格式。Phase 002 新增的 Dashboard 欄位
 * （employmentType 以下）皆可為 null，容納平台沒有該資訊的情況（見 design.md D7）。
 */
public record NormalizedJob(
        String title,
        String company,
        Long salaryMin,
        Long salaryMax,
        String salaryCurrency,
        String description,
        String employmentType,
        String seniorityLevel,
        String jobType,
        String langName,
        Integer minWorkExpYear,
        Integer numberOfOpenings,
        String city,
        String district
) {
    // 向後兼容：Phase 001 的呼叫端只帶前六個欄位，Phase 002 新增欄位一律預設 null
    public NormalizedJob(String title, String company, Long salaryMin, Long salaryMax,
                          String salaryCurrency, String description) {
        this(title, company, salaryMin, salaryMax, salaryCurrency, description,
                null, null, null, null, null, null, null, null);
    }

    // 向後兼容：add-multi-source-cakeresume 階段的呼叫端只帶到 numberOfOpenings，
    // 這次（add-job-dashboard）新增的 city/district 一律預設 null
    public NormalizedJob(String title, String company, Long salaryMin, Long salaryMax,
                          String salaryCurrency, String description, String employmentType,
                          String seniorityLevel, String jobType, String langName,
                          Integer minWorkExpYear, Integer numberOfOpenings) {
        this(title, company, salaryMin, salaryMax, salaryCurrency, description,
                employmentType, seniorityLevel, jobType, langName, minWorkExpYear, numberOfOpenings,
                null, null);
    }
}
