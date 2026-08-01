package dev.jobradar.collector.scan.cakeresume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.collector.scan.DiscoveredJob;
import dev.jobradar.collector.scan.JobListScraper;
import dev.jobradar.collector.scan.ScanResult;
import dev.jobradar.common.domain.SearchQuery;
import dev.jobradar.common.source.CakeResumeEndpoints;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * CakeResume search API 適配器。
 *
 * 與 Yourator 不同之處：
 * - 一次 API 就返回完整數據，無需 detail fetching（needsDetail=false）
 * - POST 請求，request body 包含 query / filters / page / sort_by
 * - 有明確的 total_entries 可以核對「是否真的翻完」，Yourator 完全沒有這種欄位
 *
 * 刻意不設頁數上限（見 add-crawl-improvements design.md），改用兩個安全網，理由與
 * YouratorListScraper 相同（見該類別註解）：單輪掃描時間上限 + 前後兩頁職缺集合完全
 * 相同時視為分頁卡住。兩者都不當作失敗，只記警告 + anomaly 計數器。
 */
@Component
public class CakeResumeListScraper implements JobListScraper {

    private static final Logger log = LoggerFactory.getLogger(CakeResumeListScraper.class);
    private static final String SOURCE = "cakeresume";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CollectorScanProperties properties;
    private final MeterRegistry meterRegistry;

    public CakeResumeListScraper(
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
                .baseUrl(CakeResumeEndpoints.BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.REFERER, "https://www.cake.me/jobs")
                .build();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown) {
        Duration maxScanDuration = properties.maxScanDurationFor(SOURCE, deepMode);
        List<DiscoveredJob> discovered = new ArrayList<>();
        Set<String> previousPageIds = null;
        Instant deadline = Instant.now().plus(maxScanDuration);
        int page = startPage;
        // 獨立計數「真的採用的頁數」，不要從 page 這個迴圈控制變數反推——這個變數的
        // 遞增時機只在「確定要繼續下一頁」的分支才發生，安全網 break 出去時 page 的
        // 值跟「已經真的採用幾頁」對不上，直接數採用次數才不會有 off-by-one
        int pagesScanned = 0;

        while (true) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("CakeResume scan exceeded {} time budget at page={} for query id={}, "
                                + "stopping with {} jobs already discovered",
                        maxScanDuration, page, query.id(), discovered.size());
                meterRegistry.counter("jobradar.scrape.anomaly", "source", SOURCE, "reason", "timeout").increment();
                return new ScanResult(discovered, pagesScanned, false, page);
            }

            JsonNode response = searchPage(query.location(), query.categories(), page);
            JsonNode dataArray = response.path("data");

            List<DiscoveredJob> pageItems = new ArrayList<>();
            Set<String> currentPageIds = new HashSet<>();
            for (JsonNode item : dataArray) {
                // 真實 API 回應沒有 id 欄位（已用真實資料驗證過，見端到端測試發現），
                // path 是 CakeResume 職缺頁的 slug，本身就是全站唯一識別碼，拿來當 sourceJobId
                String path = item.path("path").asText();
                String sourceJobId = path;
                // 正確網址要帶公司 slug（page.path），單用職缺 path 組出來的網址少了
                // /companies/<slug>/ 這段，會連到錯誤頁面（實測發現，見使用者回報的
                // site-reliability-engineer-sre-71702b 案例）
                String companySlug = item.path("page").path("path").asText();
                String detailUrl = "https://www.cake.me/companies/" + companySlug + "/jobs/" + path;

                // CakeResume search 已含完整數據，不需 detail fetching
                pageItems.add(new DiscoveredJob(sourceJobId, detailUrl, item, false, null));
                currentPageIds.add(sourceJobId);
            }

            // 分頁 API 常見的隱性 bug：頁碼超出真實範圍後悄悄重複回傳同一批內容，
            // total_entries 比對完全不會反映這種情況（見 class 註解），獨立檢查。視為
            // 「卡住、等同翻完」，深掃下次會重新起算（見 ScrapeCursorRepository）
            if (!currentPageIds.isEmpty() && currentPageIds.equals(previousPageIds)) {
                log.warn("CakeResume page={} returned identical job set to previous page for query id={}, "
                                + "stopping (pagination appears stuck)",
                        page, query.id());
                meterRegistry.counter("jobradar.scrape.anomaly", "source", SOURCE, "reason", "duplicate_page")
                        .increment();
                return new ScanResult(discovered, pagesScanned, true, page);
            }

            discovered.addAll(pageItems);
            previousPageIds = currentPageIds;
            pagesScanned++;

            // 淺掃早停（見 architecture.md D6）：整頁都已經是資料庫裡的舊資料就提早停止，
            // 不用等累積筆數真的追上 total_entries。深掃模式不做這個判斷，一定要翻到底。
            if (!deepMode && !currentPageIds.isEmpty() && pageIsFullyKnown.test(currentPageIds)) {
                log.debug("CakeResume page={} fully known, stopping early (light scan) for query id={}",
                        page, query.id());
                return new ScanResult(discovered, pagesScanned, true, page + 1);
            }

            // 用累積筆數判斷是否還有下一頁，不能用單頁筆數回推（最後一頁筆數通常不足整頁，
            // 用「page * 當頁筆數」回推會誤判還有下一頁，多打一次空請求）
            int totalEntries = response.path("total_entries").asInt(0);
            boolean hasNextPage = discovered.size() < totalEntries;

            if (!hasNextPage) {
                return new ScanResult(discovered, pagesScanned, true, page + 1);
            }

            page++;
            sleep(properties.requestIntervalMillis());
        }
    }

    private JsonNode searchPage(String location, List<String> professions, int page) {
        int maxRetry = properties.maxRetryFor(SOURCE);
        long backoffBaseMillis = properties.retryBackoffBaseMillisFor(SOURCE);
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                ObjectNode body = objectMapper.createObjectNode();
                // query 是必填欄位（缺了會回 422），但 add-crawl-improvements 之後不再
                // 支援自由輸入關鍵字——這裡固定送空字串，等同「不限關鍵字」，完全靠
                // professions 篩選（已實測證實是真正的聯集，見 design.md；CakeResume
                // 的 query 欄位反而不支援聯集語意，這也是拿掉它的原因之一）。
                body.put("query", "");

                ObjectNode filters = objectMapper.createObjectNode();
                // filters.location（單數、字串）是 no-op，實測拿明顯不相關的地區測試
                // total_entries 完全不變。正確欄位是 filters.locations（複數、陣列），
                // 值必須是 available_facets.locations 回傳的完整字串
                // （如「信義區, 台北市, 台灣」），不能只填「Taipei」這種簡短值。
                if (location != null && !location.isBlank()) {
                    filters.putArray("locations").add(location);
                }
                // professions 代碼來自 available_facets.professions（如 "it_back-end-engineer"），
                // 已驗證單一值即可正確過濾，不像 Yourator 的 category[] 有單值不可靠的限制
                if (professions != null && !professions.isEmpty()) {
                    var professionsArray = filters.putArray("professions");
                    professions.forEach(professionsArray::add);
                }
                body.set("filters", filters);

                body.put("page", page);
                body.put("sort_by", "latest");

                String response = restClient.post()
                        .uri(CakeResumeEndpoints.SEARCH_PATH)
                        .body(body)
                        .retrieve()
                        .body(String.class);

                return objectMapper.readTree(response);
            } catch (HttpClientErrorException.Forbidden | HttpServerErrorException.ServiceUnavailable e) {
                // 疑似風控相關（見 architecture.md D19）：不重試，直接失敗——對這類錯誤
                // 重試只會浪費請求、拉高被判定高風險的機率，跟 429/逾時的處理原則不同
                meterRegistry.counter("jobradar.scrape.anomaly", "source", SOURCE, "reason", "blocked").increment();
                throw new IllegalStateException("CakeResume returned " + e.getStatusCode() + " for page " + page, e);
            } catch (HttpClientErrorException.TooManyRequests e) {
                meterRegistry.counter("jobradar.scrape.retry", "source", SOURCE, "reason", "rate_limited").increment();
                if (attempt >= maxRetry) {
                    throw new IllegalStateException("CakeResume rate limited after " + maxRetry + " retries", e);
                }
                long backoffMillis = backoffBaseMillis * attempt;
                log.warn("CakeResume returned 429 for page={}, retry {} after {}ms", page, attempt, backoffMillis);
                sleep(backoffMillis);
            } catch (ResourceAccessException e) {
                // 連線/讀取逾時等 I/O 層級的偶發問題，跟 429 一樣值得重試——沒有頁數上限
                // 之後一輪掃描要打的請求數變多，偶發逾時的機率也跟著變高（見建構子註解），
                // 沒有這個重試的話，任何一次偶發逾時就會讓整輪掃描全部作廢
                meterRegistry.counter("jobradar.scrape.retry", "source", SOURCE, "reason", "io_timeout").increment();
                if (attempt >= maxRetry) {
                    throw new IllegalStateException(
                            "CakeResume request failed after " + maxRetry + " retries (page " + page + ")", e);
                }
                long backoffMillis = backoffBaseMillis * attempt;
                log.warn("CakeResume I/O error for page={}, retry {} after {}ms: {}",
                        page, attempt, backoffMillis, e.getMessage());
                sleep(backoffMillis);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to fetch CakeResume page " + page, e);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rate limiting CakeResume requests", e);
        }
    }
}
