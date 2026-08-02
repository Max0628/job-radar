package dev.jobradar.collector.scan;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.util.List;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * reachedEnd/nextPageToResume：只有 deepMode=true 的呼叫，ScanService 才會參考這兩個欄位
 * 決定怎麼寫 scrape_cursors（見 architecture.md D6）。reachedEnd 涵蓋「真的沒有下一頁」跟
 * 「分頁卡住的既有安全網觸發」兩種情況（後者視為卡住、不值得下次原地接續）；只有被時間
 * 預算打斷時才是 false，此時 nextPageToResume 是下次深掃該從哪一頁繼續。
 */
@Value
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ScanResult {
    List<DiscoveredJob> discovered;
    int pagesScanned;
    boolean reachedEnd;
    int nextPageToResume;
}
