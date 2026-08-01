package dev.jobradar.collector.scan;

import dev.jobradar.common.domain.SearchQuery;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 每個來源各自實作的 list scraper adapter（見 architecture.md D3）。
 * 職責只到「發現＋最低限度識別」，不做正規化、不查資料庫——淺掃早停需要的「這頁職缺
 * 存不存在」判斷，由呼叫端（ScanService）透過 pageIsFullyKnown 這個 predicate 注入，
 * 不是讓 scraper 自己接資料庫（見 architecture.md D6）。
 */
public interface JobListScraper {

    String source();

    /**
     * @param deepMode 見 architecture.md D6：true 時關閉早停、一定要翻到真的沒有下一頁；
     *                 false（預設情況）翻到 pageIsFullyKnown 判斷「整頁已知」就提早停止
     * @param startPage 只有 deepMode=true 時有意義（深掃接續用），淺掃永遠從第 1 頁開始，
     *                  呼叫端應傳 1
     * @param pageIsFullyKnown 只有 deepMode=false 時會被呼叫：輸入這一頁所有職缺的
     *                         sourceJobId，回傳 true 代表整頁都已經是資料庫裡的舊資料
     */
    ScanResult scan(SearchQuery query, boolean deepMode, int startPage, Predicate<Set<String>> pageIsFullyKnown);
}
