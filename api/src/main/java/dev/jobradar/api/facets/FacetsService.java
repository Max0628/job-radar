package dev.jobradar.api.facets;

import dev.jobradar.common.source.Source;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Value;
import lombok.experimental.Accessors;
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

    private final Map<Source, FacetsClient> clientsBySource;
    private final Map<Source, CachedEntry> cache = new ConcurrentHashMap<>();

    public FacetsService(List<FacetsClient> clients) {
        this.clientsBySource = Source.indexBy(clients, FacetsClient::source);
    }

    /**
     * source 在 HTTP 邊界維持 String（來自 {@code @PathVariable}），內部才轉成 Source——
     * 無法辨識的字串跟「有辨識但沒有對應 FacetsClient」統一回同一種 400 錯誤訊息，
     * 維持跟轉換前一致的行為。
     */
    public SourceFacets getFacets(String source) {
        Source parsedSource = parseSourceOrNull(source);
        FacetsClient client = parsedSource == null ? null : clientsBySource.get(parsedSource);
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "source must be one of " + clientsBySource.keySet());
        }

        CachedEntry cached = cache.get(parsedSource);
        if (cached != null && cached.fetchedAt().plus(TTL).isAfter(Instant.now())) {
            return cached.facets();
        }

        SourceFacets facets = client.fetch();
        cache.put(parsedSource, new CachedEntry(facets, Instant.now()));
        return facets;
    }

    private Source parseSourceOrNull(String source) {
        try {
            return Source.fromValue(source);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Value
    @Accessors(fluent = true)
    private static class CachedEntry {
        SourceFacets facets;
        Instant fetchedAt;
    }
}
