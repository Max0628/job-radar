package dev.jobradar.api.searchquery;

import dev.jobradar.common.domain.SearchQuery;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * search_queries 的 CRUD 端點，供 Dashboard 配置台使用（見
 * add-job-dashboard/specs/search-query-management-api）。分頁/排序慣例配合
 * React Admin：_start/_end/_sort/_order + X-Total-Count header。
 *
 * 驗證/業務規則（categories 不可空、單一 Yourator 分類警告）都移到
 * {@link SearchQueryService}，這裡只做 HTTP 輸入輸出轉換跟回應 header 組裝。
 */
@RestController
@RequiredArgsConstructor
public class SearchQueryController {

    private final SearchQueryService searchQueryService;

    @GetMapping("/api/search-queries")
    public ResponseEntity<List<SearchQuery>> list(
            @RequestParam(name = "_start", defaultValue = "0") int start,
            @RequestParam(name = "_end", defaultValue = "20") int end,
            @RequestParam(name = "_sort", defaultValue = "id") String sort,
            @RequestParam(name = "_order", defaultValue = "ASC") String order
    ) {
        List<SearchQuery> items = searchQueryService.list(start, end, sort, order);
        long total = searchQueryService.count();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_RANGE, "search-queries %d-%d/%d".formatted(start, end, total))
                .header("X-Total-Count", String.valueOf(total))
                .body(items);
    }

    @GetMapping("/api/search-queries/{id}")
    public SearchQuery getOne(@PathVariable long id) {
        return searchQueryService.findOne(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/api/search-queries")
    public ResponseEntity<SearchQuery> create(@RequestBody SearchQuery request) {
        SearchQuery created = searchQueryService.create(request);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        addSingleCategoryWarningIfNeeded(response, request);
        return response.body(created);
    }

    @PutMapping("/api/search-queries/{id}")
    public ResponseEntity<SearchQuery> update(@PathVariable long id, @RequestBody SearchQuery request) {
        SearchQuery updated = searchQueryService.update(id, request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        addSingleCategoryWarningIfNeeded(response, request);
        return response.body(updated);
    }

    /**
     * 回應帶 {"id": ...} 而不是空 body——ra-data-json-server 的 delete() 會讀回應 body
     * 當作刪除結果（json-server 本身的慣例），空 body（204）在前端會被解析成
     * data: undefined，可能讓 React Admin 的列表快取更新出問題。
     */
    @DeleteMapping("/api/search-queries/{id}")
    public ResponseEntity<Map<String, Long>> delete(@PathVariable long id) {
        boolean deleted = searchQueryService.delete(id);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok(Map.of("id", id));
    }

    private void addSingleCategoryWarningIfNeeded(ResponseEntity.BodyBuilder response, SearchQuery request) {
        if (searchQueryService.needsSingleCategoryWarning(request)) {
            response.header("X-Warning",
                    "Yourator category[] filtering is unreliable with a single value; "
                            + "consider bundling at least 2 categories.");
        }
    }
}
