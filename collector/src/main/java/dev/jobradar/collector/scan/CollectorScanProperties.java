package dev.jobradar.collector.scan;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * activeHoursStart/activeHoursEnd：台灣時間（Asia/Taipei）的爬蟲活躍時段（24 小時制，
 * start 含、end 不含）。半夜掃描對一般求職網站來說是不自然的流量模式（真實使用者不會
 * 半夜大量瀏覽職缺），刻意把排程限制在一般人會使用求職網站的時段內，降低被平台判定為
 * 異常流量、觸發封鎖的風險。預設 8-23 點，涵蓋上班時段與下班後瀏覽的晚間時段。
 *
 * maxRetry/maxScanDurationMinutes/retryBackoffBaseMillis：全域預設值，取代原本逐字重複在
 * 各 ListScraper 裡的常數（見 architecture.md D23）。sources 允許個別來源覆寫，null 代表
 * 沒有覆寫、退回全域預設——現階段沒有任何來源需要覆寫，這裡先把結構準備好。
 *
 * deepScanIntervalHours：距離上次深掃完成超過這個時數，這輪就跑深掃模式，否則跑淺掃
 * （見 architecture.md D6）。deepScanMaxDurationMinutes：深掃自己的單次時間預算，比淺掃
 * 的 maxScanDurationMinutes 更寬，降低需要跨天接續的次數。
 */
@ConfigurationProperties(prefix = "collector.scan")
public record CollectorScanProperties(
        long tickIntervalMillis,
        int jitterSeconds,
        String userAgent,
        long requestIntervalMillis,
        int activeHoursStart,
        int activeHoursEnd,
        int maxRetry,
        long maxScanDurationMinutes,
        long retryBackoffBaseMillis,
        int deepScanIntervalHours,
        long deepScanMaxDurationMinutes,
        Map<String, SourceOverrides> sources
) {
    /**
     * requestIntervalMinMillis/requestIntervalMaxMillis：104 需要「3–10 秒隨機區間」
     * 這種比 Yourator/CakeResume 固定單一間隔更保守的節流（見 architecture.md D19），
     * 兩者皆非 null 時才生效，否則退回全域固定的 requestIntervalMillis。
     */
    public record SourceOverrides(
            Integer maxRetry,
            Long maxScanDurationMinutes,
            Long retryBackoffBaseMillis,
            Long deepScanMaxDurationMinutes,
            Long requestIntervalMinMillis,
            Long requestIntervalMaxMillis
    ) {
    }

    private SourceOverrides overridesFor(String source) {
        return sources() == null ? null : sources().get(source);
    }

    public int maxRetryFor(String source) {
        SourceOverrides override = overridesFor(source);
        return (override != null && override.maxRetry() != null) ? override.maxRetry() : maxRetry();
    }

    public Duration maxScanDurationFor(String source, boolean deepMode) {
        SourceOverrides override = overridesFor(source);
        if (deepMode) {
            long minutes = (override != null && override.deepScanMaxDurationMinutes() != null)
                    ? override.deepScanMaxDurationMinutes()
                    : deepScanMaxDurationMinutes();
            return Duration.ofMinutes(minutes);
        }
        long minutes = (override != null && override.maxScanDurationMinutes() != null)
                ? override.maxScanDurationMinutes()
                : maxScanDurationMinutes();
        return Duration.ofMinutes(minutes);
    }

    public long retryBackoffBaseMillisFor(String source) {
        SourceOverrides override = overridesFor(source);
        return (override != null && override.retryBackoffBaseMillis() != null)
                ? override.retryBackoffBaseMillis()
                : retryBackoffBaseMillis();
    }

    public long requestIntervalMillisFor(String source) {
        SourceOverrides override = overridesFor(source);
        if (override != null && override.requestIntervalMinMillis() != null
                && override.requestIntervalMaxMillis() != null) {
            return ThreadLocalRandom.current().nextLong(
                    override.requestIntervalMinMillis(), override.requestIntervalMaxMillis() + 1);
        }
        return requestIntervalMillis();
    }
}
