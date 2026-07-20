package dev.jobradar.common.domain;

/**
 * search_queries 表的一列設定：某來源要用哪個關鍵字掃描、掃幾頁、多久掃一次。
 */
public record SearchQuery(
        long id,
        String source,
        String keyword,
        int maxPages,
        int intervalMinutes,
        boolean enabled
) {
}
