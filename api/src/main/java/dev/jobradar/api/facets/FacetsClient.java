package dev.jobradar.api.facets;

import dev.jobradar.common.source.Source;

/**
 * 各來源的 facets（分類/地區選單）抓取器，比照 collector 模組
 * {@code dev.jobradar.collector.scan.JobListScraper} 的 source() + 動作 這個介面形狀，
 * 但這是完全獨立的一份實作——這裡只是「打一次拿選單」，不是 collector 那套完整的
 * 翻頁/重試/限速爬蟲邏輯，兩者關注點差異夠大，不共用程式碼（見 design.md 的取捨）。
 */
public interface FacetsClient {

    Source source();

    SourceFacets fetch();
}
