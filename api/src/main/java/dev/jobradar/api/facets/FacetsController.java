package dev.jobradar.api.facets;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 給 Dashboard 配置台的分類/地區選單用（見 add-crawl-improvements design.md）。
 * 刻意放在 api 模組——這是唯一對外開 Ingress 的後端服務，frontend 只認得 `/api/*`
 * 這個路徑轉發給 api（見 architecture.md D13），collector 完全沒有對外的路，
 * 放在那邊前端根本連不到。
 */
@RestController
public class FacetsController {

    private final FacetsService facetsService;

    public FacetsController(FacetsService facetsService) {
        this.facetsService = facetsService;
    }

    @GetMapping("/api/sources/{source}/facets")
    public SourceFacets facets(@PathVariable String source) {
        return facetsService.getFacets(source);
    }
}
