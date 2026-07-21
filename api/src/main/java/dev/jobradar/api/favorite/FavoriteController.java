package dev.jobradar.api.favorite;

import dev.jobradar.common.domain.Favorite;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * favorites 的 CRUD 端點（見 add-job-dashboard/specs/job-favorites）。就算目前前端不打算
 * 把它做成獨立的 List 頁面（比較可能是 jobs 畫面上的收藏按鈕），list 端點還是照 React Admin
 * data provider 的慣例回 X-Total-Count——ra-data-json-server 的 getList() 缺這個 header
 * 會直接丟例外，一旦之後真的想加「我的收藏」頁面，這裡不用再回頭補。
 */
@RestController
public class FavoriteController {

    private final FavoriteRepository repository;

    public FavoriteController(FavoriteRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/favorites")
    public ResponseEntity<List<Favorite>> list() {
        List<Favorite> favorites = repository.findAll();
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(favorites.size()))
                .body(favorites);
    }

    public record CreateFavoriteRequest(String source, String sourceJobId) {
    }

    @PostMapping("/api/favorites")
    @ResponseStatus(HttpStatus.CREATED)
    public Favorite create(@RequestBody CreateFavoriteRequest request) {
        return repository.insertIfAbsent(request.source(), request.sourceJobId());
    }

    /**
     * 回應帶 {"id": ...}，理由同 SearchQueryController.delete()：
     * ra-data-json-server 會讀 DELETE 回應 body 當結果，空 body 可能讓前端解析出問題。
     */
    @DeleteMapping("/api/favorites/{id}")
    public ResponseEntity<Map<String, Long>> delete(@PathVariable long id) {
        boolean deleted = repository.delete(id);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok(Map.of("id", id));
    }
}
