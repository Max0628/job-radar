package dev.jobradar.common.domain;

import java.util.List;

/**
 * search_queries 表的一列設定：某來源要用哪些分類掃描、多久掃一次。
 *
 * location 可為 null（代表不限地區），格式由各來源 adapter 自行解讀：
 * Yourator 是 area code（如 "TPE"）；CakeResume 必須是 available_facets.locations
 * 回傳的完整字串（如「信義區, 台北市, 台灣」），不能只填地名縮寫——這個欄位若填錯格式，
 * CakeResume 端會靜默不過濾（見 add-job-dashboard/design.md D8 決策 3）。
 *
 * categories 是唯一還能限縮搜尋範圍的欄位（見 add-crawl-improvements design.md：
 * 拿掉自由輸入的 keyword，兩個平台的分類/professions 多選皆已實測證實是真正的聯集，
 * CakeResume 的 query 欄位反而不支援聯集語意，用分類取代更一致），**API 層要求至少
 * 1 個值**，不能留空（見 SearchQueryController 的驗證）。格式由各來源 adapter 自行
 * 解讀：Yourator 是分類中文名稱（如「後端工程」，來自 /api/v4/job_categories），且
 * 務必至少帶 2 個值——單一分類值的過濾行為不可靠（部分分類單獨帶會被忽略，見
 * design.md D8 決策 1）；CakeResume 是 professions 代碼（如 "it_back-end-engineer"，
 * 來自 available_facets.professions）。
 */
public record SearchQuery(
        long id,
        String source,
        String location,
        List<String> categories,
        int intervalMinutes,
        boolean enabled
) {
}
