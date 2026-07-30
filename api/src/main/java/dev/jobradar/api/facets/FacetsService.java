package dev.jobradar.api.facets;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 手動實作的簡易 TTL 記憶體快取，只有 2 個 key（yourator/cakeresume），不需要為此
 * 引入 Spring Cache 抽象層或額外快取套件——這些選單資料幾乎不會變（分類/地區清單），
 * 不需要精細的淘汰策略，時間到就整包重抓即可（見 add-crawl-improvements design.md）。
 */
@Service
public class FacetsService {

    private static final Duration TTL = Duration.ofHours(12);

    private final Map<String, FacetsClient> clientsBySource;
    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public FacetsService(List<FacetsClient> clients) {
        this.clientsBySource = clients.stream().collect(Collectors.toMap(FacetsClient::source, c -> c));
    }

    public SourceFacets getFacets(String source) {
        FacetsClient client = clientsBySource.get(source);
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "source must be one of " + clientsBySource.keySet());
        }

        CachedEntry cached = cache.get(source);
        if (cached != null && cached.fetchedAt().plus(TTL).isAfter(Instant.now())) {
            return cached.facets();
        }

        SourceFacets facets = client.fetch();
        cache.put(source, new CachedEntry(facets, Instant.now()));
        return facets;
    }

    private record CachedEntry(SourceFacets facets, Instant fetchedAt) {
    }
}
