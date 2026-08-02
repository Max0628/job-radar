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
 */
@RestController
@RequiredArgsConstructor
public class SearchQueryController {

    private final SearchQueryRepository repository;

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
        validateCategoriesNotEmpty(request.categories());
        SearchQuery created = repository.insert(request);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        addSingleCategoryWarningIfNeeded(response, request);
        return response.body(created);
    }

    @PutMapping("/api/search-queries/{id}")
    public ResponseEntity<SearchQuery> update(@PathVariable long id, @RequestBody SearchQuery request) {
        validateSource(request.source());
        validateCategoriesNotEmpty(request.categories());
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

    private void validateSource(String source) {
        if (!SearchQueryRepository.registeredSources().contains(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "source must be one of " + SearchQueryRepository.registeredSources());
        }
    }

    /**
     * 拿掉 keyword 之後，categories 是唯一還能限縮搜尋範圍的欄位（見
     * add-crawl-improvements design.md）——留空會變成「不限任何條件，把整個平台
     * 的職缺都抓下來」，幾乎不會是使用者真正想要的，直接在建立/更新時擋掉。
     */
    private void validateCategoriesNotEmpty(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categories must not be empty");
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
