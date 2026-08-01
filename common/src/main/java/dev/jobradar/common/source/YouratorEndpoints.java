package dev.jobradar.common.source;

/**
 * Yourator API 端點，集中宣告避免在 collector（list scraper）跟 api（facets client）
 * 兩個模組各自重複同一個 base URL（見 add-shared-source-endpoints proposal.md）。
 */
public final class YouratorEndpoints {

    public static final String BASE_URL = "https://www.yourator.co";
    public static final String JOBS_LIST_PATH = "/api/v4/jobs/";
    public static final String JOB_CATEGORIES_PATH = "/api/v4/job_categories";
    public static final String AREAS_PATH = "/api/v4/areas";

    private YouratorEndpoints() {
    }
}
