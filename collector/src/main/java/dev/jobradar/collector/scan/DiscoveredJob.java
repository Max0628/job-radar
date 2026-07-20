package dev.jobradar.collector.scan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * List scraper 對一筆職缺的最低限度識別；payload 是平台原始回傳，不做正規化（見 D3）。
 */
public record DiscoveredJob(String sourceJobId, String url, JsonNode payload) {
}
