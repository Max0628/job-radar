package dev.jobradar.worker.fetcher;

/**
 * Detail 頁面成功取得（HTTP 200）但預期的結構化內容缺失時拋出——跟暫時性錯誤
 * （逾時、429/5xx，繼續用 IllegalStateException 走一般重試路徑）不同，這種情況
 * 短時間內重試不會有變化。DetailFetcherListener 接到這個例外直接記警告 log +
 * anomaly 計數器後跳過這筆，不佔用 Kafka 重試名額、也不會每輪都進 DLQ（見
 * YouratorDetailScraper：少數頁面模板缺少 JobPosting JSON-LD 的用例）。
 */
public class DetailContentUnavailableException extends RuntimeException {

    public DetailContentUnavailableException(String message) {
        super(message);
    }
}
