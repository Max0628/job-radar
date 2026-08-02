package dev.jobradar.collector.scan.job104;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.collector.scan.DiscoveredJob;
import dev.jobradar.collector.scan.JobListScraper;
import dev.jobradar.collector.scan.ScanResult;
import dev.jobradar.collector.scan.ScrapeMetrics;
import dev.jobradar.collector.scan.ScraperRequestExecutor;
import dev.jobradar.common.domain.SearchQuery;
import dev.jobradar.common.source.Job104Endpoints;
import dev.jobradar.common.source.Source;
import dev.jobradar.common.source.SourceBlockedException;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 104 list/search API 適配器。前面有 Cloudflare（見 docs/source-api-notes.md），節流參數
 * 比 Yourator/CakeResume 更保守（見 architecture.md D19），透過
 * {@link CollectorScanProperties#requestIntervalMillisFor(String)} 取得 3–10 秒隨機區間，
 * 不是固定值。
 * <p>
 * 分頁用 {@code metadata.pagination.currentPage < lastPage} 判斷（見 design.md 決策）。
 * 已知平台限制：可觸及結果約 3000 筆上限（{@code lastPage × pagesize ≈ 3000}），翻頁
 * 迴圈到平台回的 {@code lastPage} 時本來就會自然停止，不需要額外程式碼處理。
 * <p>
 * list 資料不完整（沒有聯絡方式、福利說明全文等），跟 Yourator 同一類需要 detail fetch
 * （needsDetail=true）。detailUrl 存 slug（從 link.job 網址取路徑結尾），不是完整網址——
 * Job104DetailScraper 直接用這個 slug 組 API 路徑（見 Job104Endpoints.DETAIL_PATH_TEMPLATE），
 * 跟 Yourator detailUrl 存完整網址（給 Jsoup.connect 直接用）不同。
 */
@RequiredArgsConstructor
@Component
public class Job104ListScraper implements JobListScraper {

    private static final Logger log = LoggerFactory.getLogger(Job104ListScraper.class);
    private static final Source SOURCE = Source.JOB104;

    private final CollectorScanProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient job104RestClient;
    private final MeterRegistry meterRegistry;
    private final ScraperRequestExecutor scraperRequestExecutor;

    @Override
    public Source source() {
        return SOURCE;
    }

    @Override
    public ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown) {
        Duration maxScanDuration = properties.maxScanDurationFor(SOURCE.value(), deepMode);
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
                log.warn("104 scan exceeded {} time budget at page={} for query id={}, "
                                + "stopping with {} jobs already discovered",
                        maxScanDuration, page, query.id(), discovered.size());
                meterRegistry.counter(ScrapeMetrics.ANOMALY, ScrapeMetrics.SOURCE_TAG, SOURCE.value(), ScrapeMetrics.REASON_TAG, "timeout").increment();
                return new ScanResult(discovered, pagesScanned, false, page);
            }

            JsonNode response = fetchPage(query.location(), query.categories(), page);
            JsonNode dataArray = response.path("data");

            List<DiscoveredJob> pageItems = new ArrayList<>();
            Set<String> currentPageIds = new HashSet<>();
            for (JsonNode item : dataArray) {
                String sourceJobId = item.path("jobNo").asText();
                String jobUrl = item.path("link").path("job").asText();
                String slug = extractSlug(jobUrl);
                pageItems.add(new DiscoveredJob(sourceJobId, jobUrl, item, true, slug));
                currentPageIds.add(sourceJobId);
            }

            // 分頁 API 常見的隱性 bug：頁碼超出真實範圍後悄悄重複回傳同一批內容，
            // metadata.pagination 完全不會反映這種情況，獨立檢查。視為「卡住、等同
            // 翻完」，深掃下次會重新起算（見 ScrapeCursorRepository）
            if (!currentPageIds.isEmpty() && currentPageIds.equals(previousPageIds)) {
                log.warn("104 page={} returned identical job set to previous page for query id={}, "
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
            // 不用等翻到 lastPage。深掃模式不做這個判斷，一定要翻到底（或翻到平台
            // lastPage=100 的硬上限，見 class 註解）。
            if (!deepMode && !currentPageIds.isEmpty() && pageIsFullyKnown.test(currentPageIds)) {
                log.debug("104 page={} fully known, stopping early (light scan) for query id={}",
                        page, query.id());
                return new ScanResult(discovered, pagesScanned, true, page + 1);
            }

            int currentPage = response.path("metadata").path("pagination").path("currentPage").asInt(page);
            int lastPage = response.path("metadata").path("pagination").path("lastPage").asInt(currentPage);
            boolean hasNextPage = currentPage < lastPage;

            if (!hasNextPage) {
                return new ScanResult(discovered, pagesScanned, true, page + 1);
            }

            page++;
            scraperRequestExecutor.pace(properties.requestIntervalMillisFor(SOURCE.value()), "104");
        }
    }

    private JsonNode fetchPage(String areaCode, List<String> jobCategories, int page) {
        return scraperRequestExecutor.withRetry(
                "104", SOURCE.value(), page,
                properties.maxRetryFor(SOURCE.value()), properties.retryBackoffBaseMillisFor(SOURCE.value()),
                // 疑似 Cloudflare 風控相關（見 architecture.md D19）：拋專用例外（而非
                // IllegalStateException）讓 ScanService 能專門對這個情況觸發自動停用
                // （見 add-104-source/design.md「自動關閉」決策）
                e -> new SourceBlockedException(SOURCE, "104 returned " + e.getStatusCode() + " for page " + page, e),
                () -> {
                    String jobcat = jobCategories == null ? "" : String.join(",", jobCategories);
                    String body = job104RestClient.get()
                            .uri(uriBuilder -> {
                                uriBuilder.path(Job104Endpoints.LIST_PATH)
                                        .queryParam("jobcat", jobcat)
                                        .queryParam("jobsource", "joblist_search")
                                        .queryParam("mode", "l")
                                        .queryParam("page", page)
                                        .queryParam("pagesize", 30);
                                if (areaCode != null && !areaCode.isBlank()) {
                                    uriBuilder.queryParam("area", areaCode);
                                }
                                return uriBuilder.build();
                            })
                            .retrieve()
                            .body(String.class);
                    return objectMapper.readTree(body);
                });
    }

    private String extractSlug(String jobUrl) {
        if (jobUrl == null || jobUrl.isBlank()) {
            return jobUrl;
        }
        String withoutQuery = jobUrl.split("\\?", 2)[0];
        int lastSlash = withoutQuery.lastIndexOf('/');
        return lastSlash >= 0 ? withoutQuery.substring(lastSlash + 1) : withoutQuery;
    }
}
