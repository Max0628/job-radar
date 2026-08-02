package dev.jobradar.collector.scan;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 三個 {@link JobListScraper} 實作共用的「對外部平台禮貌發請求」邏輯（見 CLAUDE.md
 * 「爬蟲禮貌是硬規則」）：429／I-O 逾時重試＋遞增退避、翻頁間的節流間隔。
 *
 * 403/503（疑似風控）不重試，但丟哪一種例外是業務語意，留給呼叫端決定——104 需要丟
 * {@code SourceBlockedException} 觸發自動停用（見 add-104-source/design.md），其餘來源
 * 丟一般的 {@code IllegalStateException}，不下放到這裡，用 {@code onBlocked} 讓呼叫端
 * 自行組裝。
 */
@RequiredArgsConstructor
@Component
public class ScraperRequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScraperRequestExecutor.class);

    private final MeterRegistry meterRegistry;

    @FunctionalInterface
    public interface Request<T> {
        T fetch() throws Exception;
    }

    public <T> T withRetry(
            String sourceLabel,
            String metricSource,
            int page,
            int maxRetry,
            long backoffBaseMillis,
            Function<HttpStatusCodeException, RuntimeException> onBlocked,
            Request<T> request
    ) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return request.fetch();
            } catch (HttpClientErrorException.Forbidden | HttpServerErrorException.ServiceUnavailable e) {
                meterRegistry.counter(ScrapeMetrics.ANOMALY, ScrapeMetrics.SOURCE_TAG, metricSource, ScrapeMetrics.REASON_TAG, "blocked").increment();
                throw onBlocked.apply(e);
            } catch (HttpClientErrorException.TooManyRequests e) {
                meterRegistry.counter(ScrapeMetrics.RETRY, ScrapeMetrics.SOURCE_TAG, metricSource, ScrapeMetrics.REASON_TAG, "rate_limited").increment();
                if (attempt >= maxRetry) {
                    throw new IllegalStateException(sourceLabel + " rate limited after " + maxRetry + " retries", e);
                }
                long backoffMillis = backoffBaseMillis * attempt;
                log.warn("{} returned 429 for page={}, retry {} after {}ms", sourceLabel, page, attempt, backoffMillis);
                pace(backoffMillis, sourceLabel);
            } catch (ResourceAccessException e) {
                meterRegistry.counter(ScrapeMetrics.RETRY, ScrapeMetrics.SOURCE_TAG, metricSource, ScrapeMetrics.REASON_TAG, "io_timeout").increment();
                if (attempt >= maxRetry) {
                    throw new IllegalStateException(
                            sourceLabel + " request failed after " + maxRetry + " retries (page " + page + ")", e);
                }
                long backoffMillis = backoffBaseMillis * attempt;
                log.warn("{} I/O error for page={}, retry {} after {}ms: {}",
                        sourceLabel, page, attempt, backoffMillis, e.getMessage());
                pace(backoffMillis, sourceLabel);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to fetch " + sourceLabel + " page " + page, e);
            }
        }
    }

    public void pace(long millis, String sourceLabel) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rate limiting " + sourceLabel + " requests", e);
        }
    }
}
