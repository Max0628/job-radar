package dev.jobradar.common.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.jobradar.common.source.Source;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * search_queries 表的一列設定：某來源要用哪些分類掃描、多久掃一次。
 *
 * location 可為 null（代表不限地區），格式由各來源 adapter 自行解讀：
 * Yourator 是 area code（如 "TPE"）；CakeResume 必須是 available_facets.locations
 * 回傳的完整字串（如「信義區, 台北市, 台灣」），不能只填地名縮寫——這個欄位若填錯格式，
 * CakeResume 端會靜默不過濾（見 add-job-dashboard/design.md D8 決策 3）。
 *
 * categories 是唯一還能限縮搜尋範圍的欄位（見 add-crawl-improvements design.md：
 * 拿掉自由輸入的 keyword，兩個平台的分類/professions 多選皆已實測證實是真正的聯集，
 * CakeResume 的 query 欄位反而不支援聯集語意，用分類取代更一致），**API 層要求至少
 * 1 個值**，不能留空（見 SearchQueryController 的驗證）。格式由各來源 adapter 自行
 * 解讀：Yourator 是分類中文名稱（如「後端工程」，來自 /api/v4/job_categories），且
 * 務必至少帶 2 個值——單一分類值的過濾行為不可靠（部分分類單獨帶會被忽略，見
 * design.md D8 決策 1）；CakeResume 是 professions 代碼（如 "it_back-end-engineer"，
 * 來自 available_facets.professions）。
 *
 * disabledReason 可為 null；非 null 代表這筆是被系統自動停用的（目前只有 104 的
 * blocked 偵測會寫入，見 add-104-source/design.md「自動關閉」決策），跟使用者手動
 * 停用區分開——重新啟用（把 enabled 存回 true）時 api 模組會自動把這個欄位清成
 * null，不需要使用者另外清除。
 *
 * {@code @Jacksonized}：這個類別會被 Jackson 反序列化（`SearchQueryController` 的
 * `@RequestBody SearchQuery`），Lombok `@Value` 單獨使用時沒有無參數建構子，Jackson
 * 預設不知道怎麼從 JSON 建構物件，`@Builder @Jacksonized` 讓 Lombok 生成 Jackson
 * 認得的 builder 解決這個問題。
 *
 * {@code @AllArgsConstructor}：`@Value` + `@Builder` 組合預設會把全參數建構子降成
 * package-private（只留 builder 當唯一建構方式），但既有程式碼多處直接用
 * `new SearchQuery(...)`（見 collector/api 兩份 `SearchQueryRepository` 的 RowMapper），
 * 明確加這個註解保留 public 建構子，維持呼叫端相容、不用全部改成 builder 寫法。
 *
 * {@code @JsonAutoDetect(fieldVisibility = ANY)}：`@Accessors(fluent = true)` 讓存取
 * 方法變成無前綴（`source()` 而非 `getSource()`），Jackson 預設的 bean 內省只認得
 * `getXxx()`/`isXxx()` 命名，看不到 fluent 存取方法，序列化會生出空物件 `{}`——加這個
 * 註解讓 Jackson 直接讀 private field，繞過命名規則問題。實測過沒加這行時
 * `GET /api/search-queries` 真的回傳 `[{},{},{}]`。
 */
@Value
@Builder
@Jacksonized
@AllArgsConstructor
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SearchQuery {
    long id;
    Source source;
    String location;
    List<String> categories;
    int intervalMinutes;
    boolean enabled;
    String disabledReason;
}
