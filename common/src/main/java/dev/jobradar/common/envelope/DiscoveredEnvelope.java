package dev.jobradar.common.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * jobs.discovered 訊息：list scraper 抓到一筆職缺摘要時原封不動帶出的 payload。
 *
 * schemaVersion 2（Phase 002+）：新增 needsDetail / detailUrl 欄位，支持 per-source 邏輯。
 * - needsDetail: true 表示 Fetcher 需打 HTTP 取詳情（例 Yourator）
 * - needsDetail: false 表示 payload 已含完整資料，Fetcher no-op（例 CakeResume）
 * - detailUrl: Yourator 用於打 detail 頁；CakeResume 為 null
 *
 * payload 是平台原始回傳的 list item，Fetcher 不應依賴其結構（可能因平台改版 break）。
 * 改為依賴 sourceJobId / detailUrl 等標準欄位。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscoveredEnvelope(
        int schemaVersion,
        String source,
        String sourceJobId,
        Instant scrapedAt,
        String url,
        boolean needsDetail,
        String detailUrl,
        JsonNode payload
) {
    public DiscoveredEnvelope(String source, String sourceJobId, Instant scrapedAt, String url,
                            boolean needsDetail, String detailUrl, JsonNode payload) {
        this(EnvelopeVersion.CURRENT, source, sourceJobId, scrapedAt, url, needsDetail, detailUrl, payload);
    }

    // 向後兼容：舊訊息沒有 needsDetail / detailUrl，視為舊版本（schemaVersion=1）
}
