package dev.jobradar.worker.fetcher.yourator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.worker.fetcher.DetailScraper;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.time.Instant;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Yourator detail 頁是伺服端渲染 HTML，內嵌 schema.org JobPosting JSON-LD（見 design.md 附錄）。
 * 直接解析該 script tag，不用處理整頁 DOM。
 *
 * 最低間隔用明確的 synchronized gate 保證（見 architecture.md「同來源...間隔 ≥1s」），
 * 不單靠 Resilience4j RateLimiter：實測 RateLimiter 在併發 consumer thread 下允許的
 * 實際吞吐超過設定值（約 1.6 req/s，非預期的 1 req/s），gate 提供確定性上限；
 * Resilience4j 保留 @Retry 處理 429。
 */
@Component
public class YouratorDetailScraper implements DetailScraper {

    private static final String SOURCE = "yourator";
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    private final ObjectMapper objectMapper;
    private final String userAgent;
    private final Object rateGateLock = new Object();
    private Instant lastRequestAt = Instant.EPOCH;

    public YouratorDetailScraper(ObjectMapper objectMapper, @Value("${worker.fetcher.user-agent}") String userAgent) {
        this.objectMapper = objectMapper;
        this.userAgent = userAgent;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    @Retry(name = "yourator")
    public JsonNode fetch(String sourceJobId, String url) {
        awaitRateGate();
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .header("Accept", "text/html")
                    .timeout(10_000)
                    .get();

            Element script = doc.selectFirst("script[type=application/ld+json]");
            if (script == null) {
                throw new IllegalStateException("No JobPosting JSON-LD found at " + url);
            }
            return objectMapper.readTree(script.data());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to fetch Yourator detail for " + sourceJobId + " at " + url, e);
        }
    }

    private void awaitRateGate() {
        long sleepMillis;
        synchronized (rateGateLock) {
            Duration sinceLast = Duration.between(lastRequestAt, Instant.now());
            sleepMillis = sinceLast.compareTo(MIN_INTERVAL) < 0
                    ? MIN_INTERVAL.minus(sinceLast).toMillis()
                    : 0;
            lastRequestAt = Instant.now().plusMillis(sleepMillis);
        }
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while rate limiting Yourator detail requests", e);
            }
        }
    }
}
