package dev.jobradar.collector.scan;

import dev.jobradar.common.domain.SearchQuery;

/**
 * 每個來源各自實作的 list scraper adapter（見 architecture.md D3）。
 * 職責只到「發現＋最低限度識別」，不做正規化、不查資料庫。
 */
public interface JobListScraper {

    String source();

    ScanResult scan(SearchQuery query);
}
