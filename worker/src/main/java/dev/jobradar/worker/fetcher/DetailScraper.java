package dev.jobradar.worker.fetcher;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 每個來源各自實作的 detail fetcher adapter（見 architecture.md D3）。
 * 限速/重試策略在實作內處理（見 design.md），呼叫端不重試。
 */
public interface DetailScraper {

    String source();

    JsonNode fetch(String sourceJobId, String url);
}
