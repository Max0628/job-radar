package dev.jobradar.common.source;

/**
 * CakeResume API 端點，集中宣告避免在 collector（list scraper）跟 api（facets client）
 * 兩個模組各自重複同一個 base URL 與搜尋端點（見 add-shared-source-endpoints proposal.md）。
 * List scraper 跟 facets client 打的是同一支 SEARCH_PATH，只是 request body 不同。
 */
public final class CakeResumeEndpoints {

    public static final String BASE_URL = "https://api.cake.me";
    public static final String SEARCH_PATH = "/api/client/v1/jobs/search";

    private CakeResumeEndpoints() {
    }
}
