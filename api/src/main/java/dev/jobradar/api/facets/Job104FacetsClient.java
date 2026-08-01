package dev.jobradar.api.facets;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.common.source.Job104Endpoints;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 104 的分類/地區清單來自靜態參考檔（Area.json/JobCat.json，已確認無 Cloudflare，見
 * source-api-notes.md），即時打一次、之後靠 {@link FacetsService} 既有的快取機制，不用
 * 打包進程式碼（見 design.md 決策）。兩份檔案都是階層式結構（{@code no}=代碼、
 * {@code des}=中文名稱、{@code n}=子分類陣列），用同一套遞迴邏輯攤平成扁平的 Facet 清單，
 * 每一層節點都算一個獨立的可選值（不是只取葉節點）。
 */
@Component
public class Job104FacetsClient implements FacetsClient {

    private static final String SOURCE = "104";

    private final RestClient restClient;

    public Job104FacetsClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl(Job104Endpoints.STATIC_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public SourceFacets fetch() {
        return new SourceFacets(fetchFlattened(Job104Endpoints.JOB_CAT_JSON_PATH),
                fetchFlattened(Job104Endpoints.AREA_JSON_PATH));
    }

    private List<Facet> fetchFlattened(String path) {
        JsonNode root = restClient.get()
                .uri(path)
                .retrieve()
                .body(JsonNode.class);

        List<Facet> facets = new ArrayList<>();
        flatten(root, facets);
        return facets;
    }

    private void flatten(JsonNode nodes, List<Facet> out) {
        for (JsonNode node : nodes) {
            out.add(new Facet(node.path("no").asText(), node.path("des").asText()));
            JsonNode children = node.path("n");
            if (children.isArray()) {
                flatten(children, out);
            }
        }
    }
}
