package dev.jobradar.common.domain;

import java.util.List;

/**
 * search_queries 表的一列設定：某來源要用哪個關鍵字掃描、掃幾頁、多久掃一次。
 *
 * location 可為 null（代表不限地區），格式由各來源 adapter 自行解讀：
 * Yourator 是 area code（如 "TPE"）；CakeResume 必須是 available_facets.locations
 * 回傳的完整字串（如「信義區, 台北市, 台灣」），不能只填地名縮寫——這個欄位若填錯格式，
 * CakeResume 端會靜默不過濾（見 add-job-dashboard/design.md D8 決策 3）。
 *
 * categories 可為 null（代表不限分類），格式由各來源 adapter 自行解讀：
 * Yourator 是分類中文名稱（如「後端工程」，來自 /api/v4/job_categories），且務必至少帶
 * 2 個值——單一分類值的過濾行為不可靠（部分分類單獨帶會被忽略，見 design.md D8 決策 1）；
 * CakeResume 是 professions 代碼（如 "it_back-end-engineer"，來自 available_facets.professions）。
 */
public record SearchQuery(
        long id,
        String source,
        String keyword,
        String location,
        List<String> categories,
        int maxPages,
        int intervalMinutes,
        boolean enabled
) {
}
