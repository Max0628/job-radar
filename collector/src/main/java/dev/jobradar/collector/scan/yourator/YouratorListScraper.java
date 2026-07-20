package dev.jobradar.collector.scan.yourator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Yourator 沒有精確的更新時間排序（見 design.md 附錄），list 預設順序是相關性排序，
 * 不是 chronological，因此不做游標式 early termination，改為每輪固定掃描
 * query.maxPages() 頁；重複看到的職缺交由下游冪等 upsert 處理。
 */
@Component
public class YouratorListScraper implements JobListScraper {

    private static final Logger log = LoggerFactory.getLogger(YouratorListScraper.class);
    private static final String SOURCE = "yourator";
    private static final String BASE_URL = "https://www.yourator.co";
    private static final int MAX_RETRY = 3;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CollectorScanProperties properties;

    public YouratorListScraper(CollectorScanProperties properties, ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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
        List<DiscoveredJob> discovered = new ArrayList<>();
        int page = 1;
        boolean hasMore = true;

        while (hasMore && page <= query.maxPages()) {
            JsonNode body = fetchPage(query.keyword(), page);
            JsonNode payload = body.path("payload");

            for (JsonNode item : payload.path("jobs")) {
                String sourceJobId = item.path("id").asText();
                String path = item.path("path").asText();
                discovered.add(new DiscoveredJob(sourceJobId, BASE_URL + path, item));
            }

            hasMore = payload.path("hasMore").asBoolean(false);
            boolean willFetchAnotherPage = hasMore && page < query.maxPages();
            page++;

            if (willFetchAnotherPage) {
                sleep(properties.requestIntervalMillis());
            }
        }

        return new ScanResult(discovered, page - 1);
    }

    private JsonNode fetchPage(String keyword, int page) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                String body = restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/api/v4/jobs/")
                                .queryParam("keyword", keyword)
                                .queryParam("page", page)
                                .build())
                        .retrieve()
                        .body(String.class);
                return objectMapper.readTree(body);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= MAX_RETRY) {
                    throw new IllegalStateException("Yourator rate limited after " + MAX_RETRY + " retries", e);
                }
                long backoffMillis = 2000L * attempt;
                log.warn("Yourator returned 429 for keyword={} page={}, retry {} after {}ms",
                        keyword, page, attempt, backoffMillis);
                sleep(backoffMillis);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to fetch Yourator page " + page + " for keyword " + keyword, e);
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
