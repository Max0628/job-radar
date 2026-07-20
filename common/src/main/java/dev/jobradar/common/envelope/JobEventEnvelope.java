package dev.jobradar.common.envelope;

import java.time.Instant;

/**
 * jobs.events 訊息：normalizer upsert 後判斷出的新缺/變更事件，供 notifier 等下游消費。
 * 已正規化（title/company/salary 為結構化欄位），不同於 discovered/raw 的原始 payload。
 */
public record JobEventEnvelope(
        int schemaVersion,
        String source,
        String sourceJobId,
        Instant scrapedAt,
        String url,
        EventType type,
        String title,
        String company,
        String salaryText
) {
    public JobEventEnvelope(
            String source, String sourceJobId, Instant scrapedAt, String url,
            EventType type, String title, String company, String salaryText
    ) {
        this(EnvelopeVersion.CURRENT, source, sourceJobId, scrapedAt, url, type, title, company, salaryText);
    }
}
