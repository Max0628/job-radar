package dev.jobradar.common.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * jobs.discovered 訊息：list scraper 抓到一筆職缺摘要時原封不動帶出的 payload。
 * payload 是平台原始回傳的 list item，不做正規化（見 architecture.md D3）。
 */
public record DiscoveredEnvelope(
        int schemaVersion,
        String source,
        String sourceJobId,
        Instant scrapedAt,
        String url,
        JsonNode payload
) {
    public DiscoveredEnvelope(String source, String sourceJobId, Instant scrapedAt, String url, JsonNode payload) {
        this(EnvelopeVersion.CURRENT, source, sourceJobId, scrapedAt, url, payload);
    }
}
