package dev.jobradar.collector.scan;

/**
 * scrape 相關 Micrometer 名稱／tag key 常數。字串本身沒有編譯期檢查，打錯字會在
 * Prometheus 悄悄多出一條沒人在看的新 time series，而不會有任何錯誤——集中在這裡
 * 避免同一個字串在 {@link ScraperRequestExecutor} 跟三個 {@link JobListScraper}
 * 實作裡各自手打一次。
 */
public final class ScrapeMetrics {

    public static final String ANOMALY = "jobradar.scrape.anomaly";
    public static final String RETRY = "jobradar.scrape.retry";
    public static final String SOURCE_TAG = "source";
    public static final String REASON_TAG = "reason";

    private ScrapeMetrics() {
    }
}
