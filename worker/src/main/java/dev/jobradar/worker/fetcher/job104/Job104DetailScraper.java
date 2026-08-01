package dev.jobradar.worker.fetcher.job104;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.source.Job104Endpoints;
import dev.jobradar.common.source.SourceBlockedException;
import dev.jobradar.worker.fetcher.DetailScraper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 104 detail API 是乾淨 JSON API（{@code GET /api/jobs/{slug}}），不用像 Yourator 那樣解析
 * HTML 裡的 JSON-LD（見 source-api-notes.md）。{@code url} 參數是 list scraper 存進
 * DiscoveredJob.detailUrl 的 slug（不是完整網址，見 Job104ListScraper 類別註解）。
 *
 * 不用 Yourator 那種 Resilience4j {@code @Retry} 註解，改用跟 Job104ListScraper 一致的
 * 手刻三類錯誤分類（見 architecture.md D19、design.md 決策）：104 需要「403/503 不重試」
 * 這個分支，為了跟 list scraper 的分類邏輯保持一致、避免兩套不同的錯誤分類心智模型混在
 * 同一個來源裡，直接複用同一種手刻寫法。
 *
 * 速率閘門沿用 YouratorDetailScraper 的 synchronized MIN_INTERVAL 作法（見該類別註解）：
 * 單一 worker 實例內的 ConcurrentKafkaListenerContainerFactory 預設 concurrency=1，但
 * K8s 多副本部署下仍可能有多個 worker instance 同時處理 104 detail，這個 gate 只保證
 * 「同一個 JVM 內」的間隔下限，不是跨副本的全域保證——但 104 對 Cloudflare 風控更敏感
 * （見 architecture.md D19「104 專屬加強：同來源並發＝1」），先在單機層級把下限做確定，
 * 比完全沒有保證好；跨副本的全域速率控制留待之後真的需要多副本擴展時再處理。
 */
@Component
public class Job104DetailScraper implements DetailScraper {

    private static final Logger log = LoggerFactory.getLogger(Job104DetailScraper.class);
    private static final String SOURCE = "104";
    private static final int MAX_RETRY = 3;
    private static final long BACKOFF_BASE_MILLIS = 2000L;
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final Object rateGateLock = new Object();
    private Instant lastRequestAt = Instant.EPOCH;

    public Job104DetailScraper(
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry,
            @Value("${worker.fetcher.user-agent}") String userAgent
    ) {
        this.meterRegistry = meterRegistry;
        this.restClient = restClientBuilder
                .baseUrl(Job104Endpoints.BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .build();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public JsonNode fetch(String sourceJobId, String url) {
        String slug = url;
        int attempt = 0;
        while (true) {
            attempt++;
            awaitRateGate();
            try {
                return restClient.get()
                        .uri(Job104Endpoints.DETAIL_PATH_TEMPLATE.formatted(slug))
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpClientErrorException.Forbidden | HttpServerErrorException.ServiceUnavailable e) {
                // 疑似 Cloudflare 風控相關（見 architecture.md D19）：不重試，直接失敗。拋
                // 專用例外讓 DetailFetcherListener 能專門對這個情況觸發自動停用（見
                // add-104-source/design.md「自動關閉」決策）
                meterRegistry.counter("jobradar.scrape.anomaly", "source", SOURCE, "reason", "blocked").increment();
                throw new SourceBlockedException(SOURCE, "104 detail returned " + e.getStatusCode() + " for " + sourceJobId, e);
            } catch (HttpClientErrorException.TooManyRequests e) {
                meterRegistry.counter("jobradar.scrape.retry", "source", SOURCE, "reason", "rate_limited").increment();
                if (attempt >= MAX_RETRY) {
                    throw new IllegalStateException("104 detail rate limited after " + MAX_RETRY + " retries for " + sourceJobId, e);
                }
                long backoffMillis = BACKOFF_BASE_MILLIS * attempt;
                log.warn("104 detail returned 429 for {}, retry {} after {}ms", sourceJobId, attempt, backoffMillis);
                sleep(backoffMillis);
            } catch (ResourceAccessException e) {
                meterRegistry.counter("jobradar.scrape.retry", "source", SOURCE, "reason", "io_timeout").increment();
                if (attempt >= MAX_RETRY) {
                    throw new IllegalStateException(
                            "104 detail request failed after " + MAX_RETRY + " retries for " + sourceJobId, e);
                }
                long backoffMillis = BACKOFF_BASE_MILLIS * attempt;
                log.warn("104 detail I/O error for {}, retry {} after {}ms: {}",
                        sourceJobId, attempt, backoffMillis, e.getMessage());
                sleep(backoffMillis);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to fetch 104 detail for " + sourceJobId, e);
            }
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
            sleep(sleepMillis);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rate limiting 104 detail requests", e);
        }
    }
}
