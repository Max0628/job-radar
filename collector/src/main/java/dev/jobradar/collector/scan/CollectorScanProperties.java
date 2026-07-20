package dev.jobradar.collector.scan;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "collector.scan")
public record CollectorScanProperties(
        long tickIntervalMillis,
        int jitterSeconds,
        String userAgent,
        long requestIntervalMillis
) {
}
