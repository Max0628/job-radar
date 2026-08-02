package dev.jobradar.common.envelope;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.common.source.Source;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

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
 *
 * {@code @Jacksonized @Builder}：這個類別會被 Kafka 的 {@code JsonDeserializer} 反序列化
 * （見 worker 模組 KafkaConsumerConfig），需要 Jackson 認得的建構方式；{@code @AllArgsConstructor}
 * 保留 public 全參數建構子供下面的相容性建構子委派呼叫。
 *
 * {@code @JsonAutoDetect(fieldVisibility = ANY)}：fluent 存取方法 Jackson 序列化時看不到
 * （預設只認 getXxx()/isXxx()），這個類別是 collector 端 send 到 Kafka 的訊息本體，
 * 不加這個 collector 送出去的訊息會是空的 `{}`，worker 收到後全部欄位都是 null/預設值——
 * 比 REST API 回傳空物件更嚴重，是整條管線悄悄壞掉，見 SearchQuery.java 的說明。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Value
@Builder
@Jacksonized
@AllArgsConstructor
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class DiscoveredEnvelope {
    int schemaVersion;
    Source source;
    String sourceJobId;
    Instant scrapedAt;
    String url;
    boolean needsDetail;
    String detailUrl;
    JsonNode payload;

    // 向後兼容：舊訊息沒有 needsDetail / detailUrl，視為舊版本（schemaVersion=1）
    public DiscoveredEnvelope(Source source, String sourceJobId, Instant scrapedAt, String url,
                            boolean needsDetail, String detailUrl, JsonNode payload) {
        this(EnvelopeVersion.CURRENT, source, sourceJobId, scrapedAt, url, needsDetail, detailUrl, payload);
    }
}
