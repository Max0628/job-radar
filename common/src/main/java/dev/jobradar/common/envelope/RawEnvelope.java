package dev.jobradar.common.envelope;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.common.source.Source;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * jobs.raw 訊息：detail fetcher 抓到職缺完整內容時的 payload。
 * payload 是平台 detail 頁/API 的原始回傳，不做正規化（見 architecture.md D3）。
 *
 * {@code @Jacksonized @Builder}：這個類別會被 Kafka 的 {@code JsonDeserializer} 反序列化
 * （見 worker 模組 KafkaConsumerConfig），需要 Jackson 認得的建構方式；{@code @AllArgsConstructor}
 * 保留 public 全參數建構子供下面的相容性建構子委派呼叫。
 *
 * {@code @JsonAutoDetect(fieldVisibility = ANY)}：fluent 存取方法 Jackson 序列化時看不到，
 * 這個類別是 worker(fetcher) 端 send 到 Kafka 的訊息本體，見 DiscoveredEnvelope.java 說明。
 */
@Value
@Builder
@Jacksonized
@AllArgsConstructor
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class RawEnvelope {
    int schemaVersion;
    Source source;
    String sourceJobId;
    Instant scrapedAt;
    String url;
    JsonNode payload;

    public RawEnvelope(Source source, String sourceJobId, Instant scrapedAt, String url, JsonNode payload) {
        this(EnvelopeVersion.CURRENT, source, sourceJobId, scrapedAt, url, payload);
    }
}
