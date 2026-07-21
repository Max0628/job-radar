package dev.jobradar.collector.scan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * activeHoursStart/activeHoursEnd：台灣時間（Asia/Taipei）的爬蟲活躍時段（24 小時制，
 * start 含、end 不含）。半夜掃描對一般求職網站來說是不自然的流量模式（真實使用者不會
 * 半夜大量瀏覽職缺），刻意把排程限制在一般人會使用求職網站的時段內，降低被平台判定為
 * 異常流量、觸發封鎖的風險。預設 8-23 點，涵蓋上班時段與下班後瀏覽的晚間時段。
 */
@ConfigurationProperties(prefix = "collector.scan")
public record CollectorScanProperties(
        long tickIntervalMillis,
        int jitterSeconds,
        String userAgent,
        long requestIntervalMillis,
        int activeHoursStart,
        int activeHoursEnd
) {
}
