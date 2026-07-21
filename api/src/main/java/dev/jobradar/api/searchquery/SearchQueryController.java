package dev.jobradar.api.searchquery;

import dev.jobradar.common.domain.SearchQuery;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 */
@RestController
public class SearchQueryController {

    private final SearchQueryRepository repository;

    public SearchQueryController(SearchQueryRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/search-queries")
    public ResponseEntity<List<SearchQuery>> list(
            @RequestParam(name = "_start", defaultValue = "0") int start,
            @RequestParam(name = "_end", defaultValue = "20") int end,
            @RequestParam(name = "_sort", defaultValue = "id") String sort,
            @RequestParam(name = "_order", defaultValue = "ASC") String order
    ) {
        List<SearchQuery> items = repository.findAll(start, end, sort, order);
        long total = repository.count();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_RANGE, "search-queries %d-%d/%d".formatted(start, end, total))
                .header("X-Total-Count", String.valueOf(total))
                .body(items);
    }

    @GetMapping("/api/search-queries/{id}")
    public SearchQuery getOne(@PathVariable long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/api/search-queries")
    public ResponseEntity<SearchQuery> create(@RequestBody SearchQuery request) {
        validateSource(request.source());
        SearchQuery created = repository.insert(request);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        addSingleCategoryWarningIfNeeded(response, request);
        return response.body(created);
    }

    @PutMapping("/api/search-queries/{id}")
    public ResponseEntity<SearchQuery> update(@PathVariable long id, @RequestBody SearchQuery request) {
        validateSource(request.source());
        SearchQuery updated = repository.update(id, request)
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
        boolean deleted = repository.delete(id);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok(Map.of("id", id));
    }

    /**
     * search_queries 有 UNIQUE (source, keyword) 約束（V1 schema，當時假設每列 keyword
     * 都不同）。Phase 002+ 之後 keyword 常是空字串（改用 categories 當主要篩選維度，
     * 見 design.md D8），同一 source 若已有一列空 keyword，再插入一列同樣空 keyword
     * 就會撞這個約束。這裡先確保 API 回傳清楚的 409 而不是 500，底層約束是否要調整
     * （例如改成 (source, keyword, categories) 或乾脆拿掉）留待實際使用後再評估。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<String> handleDuplicateKey() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("A search query with the same source and keyword already exists. "
                        + "If you're using an empty keyword with categories, note that only one "
                        + "empty-keyword row per source is currently allowed (see known limitation "
                        + "in design.md D8).");
    }

    private void validateSource(String source) {
        if (!SearchQueryRepository.registeredSources().contains(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "source must be one of " + SearchQueryRepository.registeredSources());
        }
    }

    /**
     * Yourator 的 category[] 單一值過濾不可靠（見 design.md D8 決策 1），不擋這筆設定，
     * 但用 header 附加警告，提醒使用者最好帶至少 2 個分類值。
     */
    private void addSingleCategoryWarningIfNeeded(ResponseEntity.BodyBuilder response, SearchQuery request) {
        if ("yourator".equals(request.source())
                && request.categories() != null
                && request.categories().size() == 1) {
            response.header("X-Warning",
                    "Yourator category[] filtering is unreliable with a single value; "
                            + "consider bundling at least 2 categories.");
        }
    }
}
