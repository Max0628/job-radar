## Why

`docs/architecture.md` D21 已定案：每次掃描（淺掃/深掃）結束後要送一則摘要到 Discord，
跟告警共用同一個頻道，現階段每次都報。目前完全沒有程式碼——只有現有的
`DiscordNotifier`，那支只處理「發現新職缺」逐筆通知，不涵蓋「這輪掃了幾筆、新增幾筆、
耗時多久」這種彙總資訊。這個 change 把 D21 落實。

## What Changes

- `scrape_runs` 新增 `scan_mode`（`light`/`deep`）、`terminated_early`（boolean）、
  `report_sent_at`（TIMESTAMPTZ NULL）三個欄位，記錄回報需要的模式資訊與回報狀態
- `ScanService`（collector）呼叫 `ScrapeRunRepository.finishRunSuccess` 時多帶
  `deepMode`/`terminatedEarly` 兩個參數
- **BREAKING**（僅限本 repo 內部介面）：`ScrapeRunRepository.finishRunSuccess()`
  簽名擴充
- worker 新增 `@Scheduled` 輪詢任務（`ScanSummaryReporter`）：每分鐘檢查有沒有「已結束
  超過 10 分鐘、還沒回報過」的 `scrape_runs`，查 `jobs.first_seen_at` 落在該時間窗內的
  筆數當新增數，組訊息送 Discord，送完標記 `report_sent_at`
- 新增 `idx_jobs_source_first_seen_at` 索引（既有索引沒覆蓋這個查詢樣式）

## Non-Goals

- 不做「只在有新增/出錯時才報」的過濾（D21 已決定現階段每次都報，之後才考慮優化）
- 不做精確的 run-id 逐筆關聯新增數（D21 已決定用簡化版時間窗查詢，見 design.md）
- 不處理 104（尚未實作，等 `add-104-source` change）
- 不改動既有的 `DiscordNotifier`（逐筆新職缺通知）——這是另一個獨立元件，兩者共用
  webhook 設定但職責不同

## Capabilities

### New Capabilities
- `scan-summary-reporting`：涵蓋每輪掃描結束後的 Discord 回報內容與時機

### Modified Capabilities
（無——`openspec/specs/` 目前只有上一個 change 建立的 `scan-scheduling`，跟這次的回報
機制是不同 capability，不修改它）

## Impact

- `common` 模組：新增 Flyway migration
- `collector` 模組：`ScanService`、`ScrapeRunRepository` 簽名擴充
- `worker` 模組：新增 `ScrapeRunReportRepository`、`ScanSummaryReporter`
- 不影響 `api`/`frontend`
