package dev.jobradar.api.favorite;

import dev.jobradar.common.domain.Favorite;
import dev.jobradar.common.source.Source;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * favorites 的業務邏輯，從 FavoriteController 拉出來（見 DECIPLINE.md 的架構討論）。
 * 目前只是單純轉發給 Repository——先建立這一層是為了讓 Controller 統一走
 * Controller→Service→Repository，之後真的有業務規則（例如收藏數量上限）時
 * 有清楚的地方放，不用回頭改 Controller 的職責。
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository repository;

    public List<Favorite> list() {
        return repository.findAll();
    }

    public Favorite create(Source source, String sourceJobId) {
        return repository.insertIfAbsent(source, sourceJobId);
    }

    public boolean delete(long id) {
        return repository.delete(id);
    }
}
