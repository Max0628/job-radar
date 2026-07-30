package dev.jobradar.api.facets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * CakeResume 沒有獨立的 facets 端點，`available_facets` 是搜尋 API 回應本身附帶的欄位
 * （見 add-crawl-improvements design.md 的實測記錄）——用空篩選條件 + `per_page=1`
 * 呼叫一次搜尋，把回傳的職缺資料壓到最小，只要 facets 部分。
 */
@Component
public class CakeResumeFacetsClient implements FacetsClient {

    private static final String SOURCE = "cakeresume";
    private static final String BASE_URL = "https://api.cake.me";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 job-radar/0.1";

    // available_facets.professions 是全站所有職務類別（含行銷/業務/人資等），只保留
    // it_ 開頭（IT 相關）這個規則本身很穩定，不太會變——但 it_ 底下還混了測試/硬體
    // 相關代碼，這幾個額外排除（使用者明確表示不要測試/硬體相關職缺，見
    // add-crawl-improvements 使用者需求；同樣邏輯見 YouratorFacetsClient 排除
    // 「測試工程」跟整個硬體群組）。
    private static final Set<String> EXCLUDED_IT_CODES = Set.of(
            "it_qa-test-engineer",
            "it_firmware-engineering",
            "it_semiconductor-engineering",
            "it_digital-ic-design",
            "it_analog-ic-design",
            "it_ic-layout",
            "it_optical-engineering",
            "it_opto-electronic-engineering",
            "it_bios-engineering"
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CakeResumeFacetsClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
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
    public SourceFacets fetch() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", "");
        body.set("filters", objectMapper.createObjectNode());
        body.put("page", 1);
        body.put("per_page", 1);
        body.put("sort_by", "latest");

        JsonNode response = restClient.post()
                .uri("/api/client/v1/jobs/search")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode facets = response.path("available_facets");
        return new SourceFacets(extractCategories(facets), extractLocations(facets));
    }

    private List<Facet> extractCategories(JsonNode facets) {
        List<Facet> categories = new ArrayList<>();
        for (JsonNode profession : facets.path("professions")) {
            String code = profession.asText();
            if (code.startsWith("it_") && !EXCLUDED_IT_CODES.contains(code)) {
                categories.add(new Facet(code, displayLabel(code)));
            }
        }
        return categories;
    }

    private List<Facet> extractLocations(JsonNode facets) {
        List<Facet> locations = new ArrayList<>();
        for (JsonNode location : facets.path("locations")) {
            String value = location.asText();
            // available_facets.locations 是全世界的地點（500+ 筆），這個 homelab 只在乎
            // 台灣的職缺，篩到只剩台灣相關項目，不然下拉選單完全沒法用
            if (value.contains("台灣")) {
                locations.add(new Facet(value, value));
            }
        }
        return locations;
    }

    /**
     * professions 代碼只有 code（如 it_back-end-engineer），沒有搭配的顯示名稱
     * （已實測確認，見 design.md）——用字串轉換導出一個還算可讀的標籤：去掉 it_ 前綴、
     * 連字號換空格、每個字首大寫。不追求完美對應官方顯示文字，堪用即可。
     */
    private String displayLabel(String code) {
        String withoutPrefix = code.substring("it_".length());
        String[] words = withoutPrefix.split("-");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (label.length() > 0) {
                label.append(' ');
            }
            if (!word.isEmpty()) {
                label.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return label.toString();
    }
}
