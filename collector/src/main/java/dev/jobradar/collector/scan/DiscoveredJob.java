package dev.jobradar.collector.scan;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * List scraper 對一筆職缺的最低限度識別；payload 是平台原始回傳，不做正規化（見 D3）。
 *
 * Phase 002+: 新增 needsDetail / detailUrl
 * - needsDetail: true 表示 Fetcher 需打 HTTP 取詳情（Yourator detail 頁）
 * - detailUrl: Yourator 用於打 detail 頁；CakeResume 不用（已完整）
 *
 * {@code @AllArgsConstructor}：`@Value` 隱含的全參數建構子只要類別裡已手寫任何建構子
 * 就會被抑制，這裡手寫了下面的 3 參數相容建構子，需要明確加這個註解保留 5 參數建構子。
 */
@Value
@AllArgsConstructor
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class DiscoveredJob {
    String sourceJobId;
    String url;
    JsonNode payload;
    boolean needsDetail;
    String detailUrl;

    // 向後兼容：舊呼叫者只傳三個參數（Yourator）
    public DiscoveredJob(String sourceJobId, String url, JsonNode payload) {
        this(sourceJobId, url, payload, true, url);  // 預設需要 detail
    }
}
