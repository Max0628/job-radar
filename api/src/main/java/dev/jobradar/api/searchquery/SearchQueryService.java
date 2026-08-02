package dev.jobradar.api.searchquery;

import dev.jobradar.common.domain.SearchQuery;
import dev.jobradar.common.source.Source;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * search_queries 的業務邏輯，從 SearchQueryController 拉出來（見 DECIPLINE.md 的架構討論）：
 * 原本 validateCategoriesNotEmpty/addSingleCategoryWarningIfNeeded 這類驗證/業務規則
 * 直接寫在 Controller 私有方法裡，現在收斂到這裡，Controller 只做 HTTP 輸入輸出轉換
 * （header 組裝維持在 Controller，因為那是 HTTP 回應的展示邏輯，不是業務規則）。
 */
@Service
@RequiredArgsConstructor
public class SearchQueryService {

    private final SearchQueryRepository repository;

    public List<SearchQuery> list(int start, int end, String sort, String order) {
        return repository.findAll(start, end, sort, order);
    }

    public long count() {
        return repository.count();
    }

    public Optional<SearchQuery> findOne(long id) {
        return repository.findById(id);
    }

    public SearchQuery create(SearchQuery request) {
        validateCategoriesNotEmpty(request.categories());
        return repository.insert(request);
    }

    public Optional<SearchQuery> update(long id, SearchQuery request) {
        validateCategoriesNotEmpty(request.categories());
        return repository.update(id, request);
    }

    public boolean delete(long id) {
        return repository.delete(id);
    }

    /**
     * Yourator 的 category[] 單一值過濾不可靠（見 design.md D8 決策 1）——不擋這筆設定，
     * 由 Controller 決定要不要因此在回應加警告 header。
     */
    public boolean needsSingleCategoryWarning(SearchQuery request) {
        return request.source() == Source.YOURATOR
                && request.categories() != null
                && request.categories().size() == 1;
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
}
