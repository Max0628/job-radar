package dev.jobradar.common.source;

/**
 * 104 API 端點（見 add-shared-source-endpoints 建立的模式、docs/source-api-notes.md
 * 104 章節）。list/detail 同一個 base URL；Area.json/JobCat.json 是另一個網域
 * （static.104.com.tw，確認無 Cloudflare，見 source-api-notes.md）。
 */
public final class Job104Endpoints {

    public static final String BASE_URL = "https://www.104.com.tw";
    public static final String LIST_PATH = "/jobs/search/api/jobs";
    public static final String DETAIL_PATH_TEMPLATE = "/api/jobs/%s";

    public static final String STATIC_BASE_URL = "https://static.104.com.tw";
    public static final String AREA_JSON_PATH = "/category-tool/json/Area.json";
    public static final String JOB_CAT_JSON_PATH = "/category-tool/json/JobCat.json";

    private Job104Endpoints() {
    }
}
