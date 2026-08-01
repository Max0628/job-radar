## Why

`docs/architecture.md` D6 已經定案排程模型：三來源並行觸發、淺掃/深掃合併成同一套邏輯（一個
提早停止開關），但 Yourator/CakeResume 現有程式碼仍是循序執行、單一掃描模式，沒有實作這個
設計——排程層一直停在「紙上談兵」。同時 D23 也已決定要把兩個 ListScraper 逐字重複的
`MAX_RETRY`/`MAX_SCAN_DURATION`/退避常數抽成共用結構，為之後加入 104 這個新來源鋪路。
這個 change 把 D6、D23 的決策落實成程式碼。

## What Changes

- 三來源掃描從 `ScanScheduler` 單執行緒 for 迴圈改成虛擬執行緒平行送出，維持既有的
  per-query try/catch 隔離
- 淺掃/深掃合併成同一套掃描邏輯：預設淺掃（整頁已知即提早停止），距離上次深掃完成超過
  設定時數則改跑深掃（不提早停止，翻到真的沒有下一頁）
- 深掃可跨 tick 用 `scrape_cursors.last_page_scanned` 接續翻頁，新增
  `scrape_cursors.last_deep_scan_completed_at` 欄位記錄上次深掃完成時間
- **BREAKING**（僅限本 repo 內部介面，不影響外部 API）：`JobListScraper.scan()` 簽名從
  `scan(SearchQuery)` 改成 `scan(SearchQuery, boolean deepMode, int startPage,
  Predicate<Set<String>> pageIsFullyKnown)`；`ScanResult` 新增 `reachedEnd`/
  `nextPageToResume` 欄位；`ScrapeCursorRepository.updateAfterScan()` 拆成
  `updateAfterLightScan()`/`updateAfterDeepScan()` 兩支
- 抽離 `MAX_RETRY`/`MAX_SCAN_DURATION`/退避公式到 `CollectorScanProperties`，全域預設
  值不變，新增 per-source 覆寫結構（`sources` map），供之後的來源使用

## Capabilities

### New Capabilities
- `scan-scheduling`：涵蓋三來源排程觸發時機、並行執行、淺掃/深掃判斷與接續邏輯

### Modified Capabilities
（無——`openspec/specs/` 目前是空的，先前幾個 change 尚未 archive，沒有既有 capability
spec 可以標記為「修改」，這次統一算新增）

## Impact

- `collector` 模組：`ScanScheduler`、`ScanService`、`JobListScraper`、
  `ScrapeCursorRepository`、`CollectorScanProperties`、`YouratorListScraper`、
  `CakeResumeListScraper`，新增 `JobExistenceRepository`（collector 本地版本，供淺掃
  早停判斷用，`collector` 模組不依賴 `worker` 模組，不能重用 worker 那份同名類別）
- `common` 模組：新增 Flyway migration `V11__add_deep_scan_cursor.sql`
- 對應測試：`YouratorListScraperTest`、`CakeResumeListScraperTest`、`ScanServiceTest`、
  `ScanSchedulerTest`（機械式更新建構子呼叫），新增早停/接續情境測試
- 不影響 `worker`/`api` 模組、不影響對外部平台的請求語意（Yourator/CakeResume 的 URL、
  參數皆未變動）

## 補充說明（本 change 為補寫，非事前提案）

Phase 1（常數抽離）與 Phase 2（並行＋淺深掃合併）已於實作階段用 Claude Code 一般 Plan
Mode 完成並通過 `./gradlew :collector:test`（含新增測試），**當時未依本專案 SDD 流程走
`/opsx:propose`**，事後（使用者提出「有沒有按照 SDD 開發」的疑問後）補開此 change、
補寫 proposal/design/specs/tasks，讓歷史紀錄跟 `docs/architecture.md` D6/D19/D23 對上。
`tasks.md` 會如實反映「先寫程式碼、後補文件」這個實際發生的順序，不假裝是照標準順序做的。
