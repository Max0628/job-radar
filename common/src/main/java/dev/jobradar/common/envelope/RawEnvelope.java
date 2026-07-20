package dev.jobradar.common.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * jobs.raw 訊息：detail fetcher 抓到職缺完整內容時的 payload。
 * payload 是平台 detail 頁/API 的原始回傳，不做正規化（見 architecture.md D3）。
 */
public record RawEnvelope(
        int schemaVersion,
        String source,
        String sourceJobId,
        Instant scrapedAt,
        String url,
        JsonNode payload
) {
    public RawEnvelope(String source, String sourceJobId, Instant scrapedAt, String url, JsonNode payload) {
        this(EnvelopeVersion.CURRENT, source, sourceJobId, scrapedAt, url, payload);
    }
}
