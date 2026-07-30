package dev.jobradar.collector.scan.yourator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.collector.scan.DiscoveredJob;
import dev.jobradar.collector.scan.JobListScraper;
import dev.jobradar.collector.scan.ScanResult;
import dev.jobradar.common.domain.SearchQuery;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Yourator 沒有精確的更新時間排序（見 design.md 附錄），list 預設順序是相關性排序，
 * 不是 chronological，因此不做游標式 early termination；也沒有任何「總筆數」欄位
 * （`payload` 只有 `hasMore`，沒有 total/totalCount），翻頁純粹依賴 `hasMore` 判斷，
 * 沒有辦法像 CakeResume 那樣拿 total_entries 做二次確認（見 add-crawl-improvements
 * design.md 實測記錄）。重複看到的職缺交由下游冪等 upsert 處理。
 *
 * 刻意不設頁數上限（見 add-crawl-improvements design.md）：改用兩個對應到具體異常模式
 * 的安全網，而非任意選一個頁數當上限——
 * 1. 單輪掃描時間上限（MAX_SCAN_DURATION）：正常情況下不會被打到，只在 `hasMore`
 *    真的卡住不掉時避免整輪排程被無限拖住
 * 2. 前後兩頁完全相同的職缺集合（分頁 API 常見的隱性 bug：頁碼超出真實範圍後悄悄
 *    重複回傳同一批內容，`hasMore` 完全不會反映這種情況）
 * 兩者觸發時都不當成失敗（不拋例外、不丟掉已經抓到的資料），只記警告 log + 一個
 * 獨立的 anomaly 計數器，讓異常「看得見」但不會讓這輪掃描白費。
 */
@Component
public class YouratorListScraper implements JobListScraper {

    private static final Logger log = LoggerFactory.getLogger(YouratorListScraper.class);
    private static final String SOURCE = "yourator";
    private static final String BASE_URL = "https://www.yourator.co";
    private static final int MAX_RETRY = 3;
    private static final Duration MAX_SCAN_DURATION = Duration.ofMinutes(15);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CollectorScanProperties properties;
    private final MeterRegistry meterRegistry;

    public YouratorListScraper(
            CollectorScanProperties properties,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        // connect/read timeout 由注入的 restClientBuilder 帶（見
        // dev.jobradar.collector.config.HttpClientConfig）——刻意不在這裡呼叫
        // requestFactory()，那樣會讓單元測試的 MockRestServiceServer 綁定失效
        // （已實際踩過這個坑，見 HttpClientConfig 的類別註解）。
        this.restClient = restClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public ScanResult scan(SearchQuery query) {
        if (query.categories() != null && query.categories().size() == 1) {
            log.warn("Yourator query id={} has exactly 1 category ({}); single-value category[] "
                            + "filtering is unreliable on this API (confirmed via manual verification: "
                            + "some categories are silently ignored when sent alone). Consider bundling "
                            + "at least 2 categories per query.",
                    query.id(), query.categories().get(0));
        }

        List<DiscoveredJob> discovered = new ArrayList<>();
        Set<String> previousPageIds = null;
        Instant deadline = Instant.now().plus(MAX_SCAN_DURATION);
        int page = 1;
        // 獨立計數「真的採用的頁數」，不要從 page 這個迴圈控制變數反推——這個變數的
        // 遞增時機只在「確定要繼續下一頁」的分支才發生，安全網 break 出去時 page 的
        // 值跟「已經真的採用幾頁」對不上，直接數採用次數才不會有 off-by-one
        int pagesScanned = 0;
        boolean hasMore = true;

        while (hasMore) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("Yourator scan exceeded {} time budget at page={} for query id={}, "
                                + "stopping with {} jobs already discovered",
                        MAX_SCAN_DURATION, page, query.id(), discovered.size());
                meterRegistry.counter("jobradar.scrape.anomaly", "source", SOURCE, "reason", "timeout").increment();
                break;
            }

            JsonNode body = fetchPage(query.location(), query.categories(), page);
            JsonNode payload = body.path("payload");

            List<DiscoveredJob> pageItems = new ArrayList<>();
            Set<String> currentPageIds = new HashSet<>();
            for (JsonNode item : payload.path("jobs")) {
                String sourceJobId = item.path("id").asText();
                String path = item.path("path").asText();
                pageItems.add(new DiscoveredJob(sourceJobId, BASE_URL + path, item));
                currentPageIds.add(sourceJobId);
            }

            // 分頁 API 常見的隱性 bug：頁碼超出真實範圍後悄悄重複回傳同一批內容，
            // hasMore 完全不會反映這種情況（見 class 註解），所以獨立檢查
            if (!currentPageIds.isEmpty() && currentPageIds.equals(previousPageIds)) {
                log.warn("Yourator page={} returned identical job set to previous page for query id={}, "
                                + "stopping (pagination appears stuck)",
                        page, query.id());
                meterRegistry.counter("jobradar.scrape.anomaly", "source", SOURCE, "reason", "duplicate_page")
                        .increment();
                break;
            }

            discovered.addAll(pageItems);
            previousPageIds = currentPageIds;
            pagesScanned++;

            hasMore = payload.path("hasMore").asBoolean(false);
            page++;

            if (hasMore) {
                sleep(properties.requestIntervalMillis());
            }
        }

        return new ScanResult(discovered, pagesScanned);
    }

    private JsonNode fetchPage(String areaCode, List<String> categories, int page) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                // 正確參數是 area[]/category[]/sort。keyword 對這支 API 完全無效（見
                // design.md 側錄更正紀錄，term[] 送什麼都回同一批未過濾結果的年代已經
                // 過去），add-crawl-improvements 之後乾脆整個拿掉自由輸入的關鍵字，
                // 完全靠 category[] 篩選（已實測證實是真正的聯集，見 design.md）。
                String body = restClient.get()
                        .uri(uriBuilder -> {
                            uriBuilder.path("/api/v4/jobs/")
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
            } catch (HttpClientErrorException.TooManyRequests e) {
                meterRegistry.counter("jobradar.scrape.retry", "source", SOURCE, "reason", "rate_limited").increment();
                if (attempt >= MAX_RETRY) {
                    throw new IllegalStateException("Yourator rate limited after " + MAX_RETRY + " retries", e);
                }
                long backoffMillis = 2000L * attempt;
                log.warn("Yourator returned 429 for page={}, retry {} after {}ms", page, attempt, backoffMillis);
                sleep(backoffMillis);
            } catch (ResourceAccessException e) {
                // 連線/讀取逾時等 I/O 層級的偶發問題，跟 429 一樣值得重試——見建構子註解
                meterRegistry.counter("jobradar.scrape.retry", "source", SOURCE, "reason", "io_timeout").increment();
                if (attempt >= MAX_RETRY) {
                    throw new IllegalStateException(
                            "Yourator request failed after " + MAX_RETRY + " retries (page " + page + ")", e);
                }
                long backoffMillis = 2000L * attempt;
                log.warn("Yourator I/O error for page={}, retry {} after {}ms: {}",
                        page, attempt, backoffMillis, e.getMessage());
                sleep(backoffMillis);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to fetch Yourator page " + page, e);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rate limiting Yourator requests", e);
        }
    }
}
