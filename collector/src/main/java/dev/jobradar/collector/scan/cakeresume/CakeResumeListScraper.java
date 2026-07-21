package dev.jobradar.collector.scan.cakeresume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.collector.scan.DiscoveredJob;
import dev.jobradar.collector.scan.JobListScraper;
import dev.jobradar.collector.scan.ScanResult;
import dev.jobradar.common.domain.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * CakeResume search API 適配器。
 *
 * 與 Yourator 不同之處：
 * - 一次 API 就返回完整數據，無需 detail fetching（needsDetail=false）
 * - POST 請求，request body 包含 query / filters / page / sort_by
 * - 無明確時間排序，亦採用固定頁數掃描策略
 */
@Component
public class CakeResumeListScraper implements JobListScraper {

    private static final Logger log = LoggerFactory.getLogger(CakeResumeListScraper.class);
    private static final String SOURCE = "cakeresume";
    private static final String BASE_URL = "https://api.cake.me";
    private static final int MAX_RETRY = 3;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CollectorScanProperties properties;

    public CakeResumeListScraper(CollectorScanProperties properties, ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl(BASE_URL)
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
    public ScanResult scan(SearchQuery query) {
        List<DiscoveredJob> discovered = new ArrayList<>();
        int page = 1;

        while (page <= query.maxPages()) {
            JsonNode response = searchPage(query.keyword(), query.location(), query.categories(), page);
            JsonNode dataArray = response.path("data");

            for (JsonNode item : dataArray) {
                // 真實 API 回應沒有 id 欄位（已用真實資料驗證過，見端到端測試發現），
                // path 是 CakeResume 職缺頁的 slug，本身就是全站唯一識別碼，拿來當 sourceJobId
                String path = item.path("path").asText();
                String sourceJobId = path;
                String detailUrl = "https://www.cake.me/jobs/" + path;

                // CakeResume search 已含完整數據，不需 detail fetching
                discovered.add(new DiscoveredJob(sourceJobId, detailUrl, item, false, null));
            }

            // 用累積筆數判斷是否還有下一頁，不能用單頁筆數回推（最後一頁筆數通常不足整頁，
            // 用「page * 當頁筆數」回推會誤判還有下一頁，多打一次空請求）
            int totalEntries = response.path("total_entries").asInt(0);
            boolean hasNextPage = discovered.size() < totalEntries;

            if (!hasNextPage || page >= query.maxPages()) {
                break;
            }

            page++;
            sleep(properties.requestIntervalMillis());
        }

        return new ScanResult(discovered, page);
    }

    private JsonNode searchPage(String keyword, String location, List<String> professions, int page) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("query", keyword == null ? "" : keyword);

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
                        .uri("/api/client/v1/jobs/search")
                        .body(body)
                        .retrieve()
                        .body(String.class);

                return objectMapper.readTree(response);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= MAX_RETRY) {
                    throw new IllegalStateException("CakeResume rate limited after " + MAX_RETRY + " retries", e);
                }
                long backoffMillis = 2000L * attempt;
                log.warn("CakeResume returned 429 for keyword={} page={}, retry {} after {}ms",
                        keyword, page, attempt, backoffMillis);
                sleep(backoffMillis);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to fetch CakeResume page " + page + " for keyword " + keyword, e);
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
