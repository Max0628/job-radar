package dev.jobradar.collector.scan.yourator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.collector.scan.DiscoveredJob;
import dev.jobradar.collector.scan.JobListScraper;
import dev.jobradar.collector.scan.ScanResult;
import dev.jobradar.collector.scan.ScrapeMetrics;
import dev.jobradar.collector.scan.ScraperRequestExecutor;
import dev.jobradar.common.domain.SearchQuery;
import dev.jobradar.common.source.Source;
import dev.jobradar.common.source.YouratorEndpoints;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Yourator 沒有精確的更新時間排序（見 design.md 附錄），list 預設順序是相關性排序，
 * 不是 chronological——這代表淺掃的早停（pageIsFullyKnown，見 JobListScraper）不保證
 * 停在「真的都是舊資料」的位置，只能盡量而為（見 architecture.md D6 待確認事項）。
 * 也沒有任何「總筆數」欄位（`payload` 只有 `hasMore`，沒有 total/totalCount），翻頁純粹
 * 依賴 `hasMore` 判斷，沒有辦法像 CakeResume 那樣拿 total_entries 做二次確認（見
 * add-crawl-improvements design.md 實測記錄）。重複看到的職缺交由下游冪等 upsert 處理。
 * <p>
 * 刻意不設頁數上限（見 add-crawl-improvements design.md）：改用兩個對應到具體異常模式
 * 的安全網，而非任意選一個頁數當上限——
 * 1. 單輪掃描時間上限（MAX_SCAN_DURATION）：正常情況下不會被打到，只在 `hasMore`
 * 真的卡住不掉時避免整輪排程被無限拖住
 * 2. 前後兩頁完全相同的職缺集合（分頁 API 常見的隱性 bug：頁碼超出真實範圍後悄悄
 * 重複回傳同一批內容，`hasMore` 完全不會反映這種情況）
 * 兩者觸發時都不當成失敗（不拋例外、不丟掉已經抓到的資料），只記警告 log + 一個
 * 獨立的 anomaly 計數器，讓異常「看得見」但不會讓這輪掃描白費。
 */
@RequiredArgsConstructor
@Component
public class YouratorListScraper implements JobListScraper {

    private static final Logger log = LoggerFactory.getLogger(YouratorListScraper.class);
    private static final Source SOURCE = Source.YOURATOR;

    private final CollectorScanProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient youratorRestClient;
    private final MeterRegistry meterRegistry;
    private final ScraperRequestExecutor scraperRequestExecutor;

    @Override
    public Source source() {
        return SOURCE;
    }

    @Override
    public ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown) {
        if (query.categories() != null && query.categories().size() == 1) {
            log.warn("Yourator query id={} has exactly 1 category ({}); single-value category[] "
                            + "filtering is unreliable on this API (confirmed via manual verification: "
                            + "some categories are silently ignored when sent alone). Consider bundling "
                            + "at least 2 categories per query.",
                    query.id(), query.categories().get(0));
        }

        Duration maxScanDuration = properties.maxScanDurationFor(SOURCE.value(), deepMode);
        List<DiscoveredJob> discovered = new ArrayList<>();
        Set<String> previousPageIds = null;
        Instant deadline = Instant.now().plus(maxScanDuration);
        int page = startPage;
        // 獨立計數「真的採用的頁數」，不要從 page 這個迴圈控制變數反推——這個變數的
        // 遞增時機只在「確定要繼續下一頁」的分支才發生，安全網 break 出去時 page 的
        // 值跟「已經真的採用幾頁」對不上，直接數採用次數才不會有 off-by-one
        int pagesScanned = 0;
        boolean hasMore = true;

        while (hasMore) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("Yourator scan exceeded {} time budget at page={} for query id={}, "
                                + "stopping with {} jobs already discovered",
                        maxScanDuration, page, query.id(), discovered.size());
                meterRegistry.counter(ScrapeMetrics.ANOMALY, ScrapeMetrics.SOURCE_TAG, SOURCE.value(), ScrapeMetrics.REASON_TAG, "timeout").increment();
                return new ScanResult(discovered, pagesScanned, false, page);
            }

            JsonNode body = fetchPage(query.location(), query.categories(), page);
            JsonNode payload = body.path("payload");

            List<DiscoveredJob> pageItems = new ArrayList<>();
            Set<String> currentPageIds = new HashSet<>();
            for (JsonNode item : payload.path("jobs")) {
                String sourceJobId = item.path("id").asText();
                String path = item.path("path").asText();
                pageItems.add(new DiscoveredJob(sourceJobId, YouratorEndpoints.BASE_URL + path, item));
                currentPageIds.add(sourceJobId);
            }

            // 分頁 API 常見的隱性 bug：頁碼超出真實範圍後悄悄重複回傳同一批內容，
            // hasMore 完全不會反映這種情況（見 class 註解），所以獨立檢查。視為「卡住、
            // 等同翻完」，不是被時間預算打斷，深掃下次會重新起算（見 ScrapeCursorRepository）
            if (!currentPageIds.isEmpty() && currentPageIds.equals(previousPageIds)) {
                log.warn("Yourator page={} returned identical job set to previous page for query id={}, "
                                + "stopping (pagination appears stuck)",
                        page, query.id());
                meterRegistry.counter(ScrapeMetrics.ANOMALY, ScrapeMetrics.SOURCE_TAG, SOURCE.value(), ScrapeMetrics.REASON_TAG, "duplicate_page")
                        .increment();
                return new ScanResult(discovered, pagesScanned, true, page);
            }

            discovered.addAll(pageItems);
            previousPageIds = currentPageIds;
            pagesScanned++;

            // 淺掃早停（見 architecture.md D6）：整頁都已經是資料庫裡的舊資料就提早停止，
            // 不用等 hasMore 真的變 false。深掃模式不做這個判斷，一定要翻到底。
            if (!deepMode && !currentPageIds.isEmpty() && pageIsFullyKnown.test(currentPageIds)) {
                log.debug("Yourator page={} fully known, stopping early (light scan) for query id={}",
                        page, query.id());
                return new ScanResult(discovered, pagesScanned, true, page + 1);
            }

            hasMore = payload.path("hasMore").asBoolean(false);
            page++;

            if (hasMore) {
                scraperRequestExecutor.pace(properties.requestIntervalMillis(), "Yourator");
            }
        }

        return new ScanResult(discovered, pagesScanned, true, page);
    }

    private JsonNode fetchPage(String areaCode, List<String> categories, int page) {
        return scraperRequestExecutor.withRetry(
                "Yourator", SOURCE.value(), page,
                properties.maxRetryFor(SOURCE.value()), properties.retryBackoffBaseMillisFor(SOURCE.value()),
                // 疑似風控相關（見 architecture.md D19）：不重試，直接失敗——對這類錯誤
                // 重試只會浪費請求、拉高被判定高風險的機率，跟 429/逾時的處理原則不同
                e -> new IllegalStateException("Yourator returned " + e.getStatusCode() + " for page " + page, e),
                () -> {
                    // 正確參數是 area[]/category[]/sort。keyword 對這支 API 完全無效（見
                    // design.md 側錄更正紀錄，term[] 送什麼都回同一批未過濾結果的年代已經
                    // 過去），add-crawl-improvements 之後乾脆整個拿掉自由輸入的關鍵字，
                    // 完全靠 category[] 篩選（已實測證實是真正的聯集，見 design.md）。
                    String body = youratorRestClient.get()
                            .uri(uriBuilder -> {
                                uriBuilder.path(YouratorEndpoints.JOBS_LIST_PATH)
                                        .queryParam("sort", "most_related")
                                        .queryParam("page", page);
                                if (areaCode != null && !areaCode.isBlank()) {
                                    uriBuilder.queryParam("area[]", areaCode);
                                }
                                if (categories != null) {
                                    for (String category : categories) {
                                        uriBuilder.queryParam("category[]", category);
                                    }
                                }
                                return uriBuilder.build();
                            })
                            .retrieve()
                            .body(String.class);
                    return objectMapper.readTree(body);
                });
    }
}
