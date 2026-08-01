package dev.jobradar.api.facets;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.common.source.YouratorEndpoints;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Yourator 有獨立的官方端點可以直接拿完整分類/地區清單，不用像 CakeResume 那樣拐彎
 * 從搜尋回應裡挖（見 add-crawl-improvements design.md 的實測記錄）。
 */
@Component
public class YouratorFacetsClient implements FacetsClient {

    private static final String SOURCE = "yourator";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 job-radar/0.1";

    // 只保留跟軟體/資料/資安/系統基礎架構相關的分類群組（group id），行銷/營運/市場開發/
    // 產品設計等群組整組排除。「軟硬體系統整合（group 6）」也整組排除——裡面全是韌體/半導體/
    // 光電這類硬體職缺，不在使用者要找的範圍內（見 add-crawl-improvements 使用者需求）。
    private static final Set<Integer> ALLOWED_GROUP_IDS = Set.of(7, 8, 10, 3);

    // 「軟體開發（group 7）」這個群組裡混了測試工程，沒辦法用群組層級一次篩掉，
    // 只能額外排除這個名字（使用者明確表示不要測試相關職缺）。
    private static final Set<String> EXCLUDED_CATEGORY_NAMES = Set.of("測試工程");

    private final RestClient restClient;

    public YouratorFacetsClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl(YouratorEndpoints.BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public SourceFacets fetch() {
        return new SourceFacets(fetchCategories(), fetchLocations());
    }

    private List<Facet> fetchCategories() {
        JsonNode body = restClient.get()
                .uri(YouratorEndpoints.JOB_CATEGORIES_PATH)
                .retrieve()
                .body(JsonNode.class);

        List<Facet> categories = new ArrayList<>();
        for (JsonNode group : body.path("payload").path("categoryGroups")) {
            if (!ALLOWED_GROUP_IDS.contains(group.path("id").asInt(-1))) {
                continue;
            }
            for (JsonNode category : group.path("jobCategories")) {
                String name = category.path("name").asText();
                if (EXCLUDED_CATEGORY_NAMES.contains(name)) {
                    continue;
                }
                // Yourator 的 category[] 參數吃的是名稱本身（不是數字 id），見
                // YouratorListScraper 的既有用法，id/name 這裡刻意都用同一個字串
                categories.add(new Facet(name, name));
            }
        }
        return categories;
    }

    private List<Facet> fetchLocations() {
        JsonNode body = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(YouratorEndpoints.AREAS_PATH)
                        .queryParam("query_key[]", "job_areas")
                        .build())
                .retrieve()
                .body(JsonNode.class);

        List<Facet> locations = new ArrayList<>();
        for (JsonNode area : body.path("payload").path("areas")) {
            locations.add(new Facet(area.path("value").asText(), area.path("name").asText()));
        }
        return locations;
    }
}
