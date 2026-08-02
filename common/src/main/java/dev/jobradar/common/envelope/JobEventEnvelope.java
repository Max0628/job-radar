package dev.jobradar.common.envelope;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.jobradar.common.source.Source;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * jobs.events 訊息：normalizer upsert 後判斷出的新缺/變更事件，供 notifier 等下游消費。
 * 已正規化（title/company/salary 為結構化欄位），不同於 discovered/raw 的原始 payload。
 *
 * {@code @Jacksonized @Builder}：這個類別會被 Kafka 的 {@code JsonDeserializer} 反序列化
 * （見 worker 模組 KafkaConsumerConfig），需要 Jackson 認得的建構方式；{@code @AllArgsConstructor}
 * 保留 public 全參數建構子供下面的相容性建構子委派呼叫。
 *
 * {@code @JsonAutoDetect(fieldVisibility = ANY)}：fluent 存取方法 Jackson 序列化時看不到，
 * 這個類別是 worker(normalizer) 端 send 到 Kafka 的訊息本體，見 DiscoveredEnvelope.java 說明。
 */
@Value
@Builder
@Jacksonized
@AllArgsConstructor
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class JobEventEnvelope {
    int schemaVersion;
    Source source;
    String sourceJobId;
    Instant scrapedAt;
    String url;
    EventType type;
    String title;
    String company;
    String salaryText;

    public JobEventEnvelope(
            Source source, String sourceJobId, Instant scrapedAt, String url,
            EventType type, String title, String company, String salaryText
    ) {
        this(EnvelopeVersion.CURRENT, source, sourceJobId, scrapedAt, url, type, title, company, salaryText);
    }
}
