## Context

`scrape_runs` 目前只有寫入方法（`ScrapeRunRepository`），沒有查詢方法。`jobs` 表有
`first_seen_at` 但沒有 `(source, first_seen_at)` 的索引。既有的 `DiscordNotifier`
（worker）是 Kafka 事件驅動、即時觸發；這次的回報需要「延遲固定時間再查」，事件驅動
模型不適合（會在 consumer 裡 block）。

## Goals / Non-Goals

**Goals:**
- 每次掃描結束後，延遲一段時間送出摘要，不阻塞任何 Kafka consumer
- 摘要內容涵蓋 D21 要求的欄位：來源、模式、有沒有提早停止、掃描筆數、新增筆數、耗時

**Non-Goals:**
- 不做精確關聯（見 proposal.md）
- 不做「只在有事發生才報」的過濾

## Decisions

**用 `@Scheduled` 輪詢，不用 Kafka 事件驅動**
`DiscordNotifier` 的即時通知適合事件驅動（收到訊息就處理）；這次需要「延遲 10 分鐘」，
如果用 Kafka listener 收到掃描完成事件後 `Thread.sleep(10分鐘)`，會佔用 consumer
執行緒、卡住同一個 partition 後續訊息。改成 worker 開一個 `@Scheduled(fixedDelay=60000)`
輪詢任務，查 `scrape_runs WHERE finished_at < now() - 10min AND report_sent_at IS NULL`，
不需要新的 Kafka topic。
- 被否決：新增 `scan.summary` Kafka topic，collector 發布、worker 消費——多一個 topic
  但沒有解決「延遲觸發」這個核心問題，consumer 收到後一樣要嘛 block 一樣要嘛還是要另外
  排程，不如直接用排程

**`report_sent_at` 用來判斷「有沒有回報過」，不是額外開一張表**
`scrape_runs` 本身就是「一輪掃描」的紀錄，回報狀態是這輪掃描的附屬狀態，加欄位比開新表
自然，也不用額外處理外鍵/清理邏輯。

**新增筆數：查 `jobs.first_seen_at` 落在 `[started_at, finished_at]` 且 `source` 相符**
不用 `scrape_runs.jobs_discovered`（這個欄位現有程式碼從來沒寫過，見探索階段發現），
直接從 `jobs` 表的 ground truth 算，比依賴一個從未被正確維護的欄位可靠。

**`ScanSummaryReporter` 直接重用 `DiscordProperties`（`worker.discord.webhook-url`）**
D21 決定回報跟告警共用同一個 Discord 頻道，這裡進一步重用同一個 webhook 設定值，不用
另外開一組設定——雖然告警本身是走 Alertmanager 送到這個 webhook（跟這裡的 worker
直接送是兩條不同路徑），但目的地相同，設定值也該共用同一個來源，不要有兩份 webhook URL
設定要維護。

## Risks / Trade-offs

- **[風險] 時間窗查詢在極端情況下可能算錯新增數**（例如同一來源兩次掃描時間窗重疊）
  → 已在 architecture.md D21 記錄為已知簡化，目前排程間隔設計下風險低
- **[風險] 輪詢任務如果卡住（例如 Discord webhook 長時間逾時），會延誤其他待回報的
  run** → 緩解：每筆回報獨立 try/catch，單筆失敗不影響其他筆，且下一輪輪詢會重試
  （`report_sent_at` 只在成功送出後才更新）

## Migration Plan

- Flyway migration：`scrape_runs` 新增三欄位（皆可為 NULL/有預設值，不需要 backfill）；
  新增 `idx_jobs_source_first_seen_at`
- 無 rollback 特殊處理，新欄位不影響既有查詢
