## 1. 資料庫

- [x] 1.1 Flyway migration：`scrape_runs` 新增 `scan_mode VARCHAR(8)`、
      `terminated_early BOOLEAN`、`report_sent_at TIMESTAMPTZ`
- [x] 1.2 同一份 migration 新增 `idx_jobs_source_first_seen_at ON jobs (source,
      first_seen_at)`

## 2. collector：寫入模式資訊

- [x] 2.1 `ScrapeRunRepository.finishRunSuccess` 簽名加上 `scanMode`/`terminatedEarly`
      兩個參數，UPDATE 語句一併寫入
- [x] 2.2 `ScanService.runScan` 呼叫 `finishRunSuccess` 時傳入 `deepMode ? "deep" :
      "light"` 與 `!result.reachedEnd()`
- [x] 2.3 更新 `ScanServiceTest` 既有的 mock 驗證——確認過沒有既有測試斷言這個呼叫的
      參數，不需要改動

## 3. worker：查詢與回報元件

- [x] 3.1 新增 `ScrapeRunReportRepository`：`findUnreportedFinishedRuns(Instant
      before)` 回傳待回報的 run 清單（含 source/scan_mode/terminated_early/
      started_at/finished_at/pages_scanned/jobs_seen）、`countNewJobs(String source,
      Instant from, Instant to)`、`markReported(long runId)`
- [x] 3.2 新增 `ScanSummaryReporter`：`@Scheduled(fixedDelay = 60000)`，查待回報 run，
      逐筆查新增數、組 Discord embed 訊息、送出、標記已回報；單筆失敗只記 log，不影響
      其他筆（另外補上 `WorkerApplication` 缺的 `@EnableScheduling`，之前沒有任何
      `@Scheduled` 元件所以沒開過）
- [x] 3.3 沿用既有 `DiscordProperties`（`worker.discord.webhook-url`），不新增設定值

## 4. 測試

- [x] 4.1 `ScanSummaryReporterTest`：比照 `DiscordNotifierTest` 用
      `MockRestServiceServer` + `SimpleMeterRegistry`，`ScrapeRunReportRepository`
      用 Mockito mock，涵蓋：有待回報 run 時送出訊息並標記、沒有待回報 run 時不送、
      單筆送出失敗不影響其他筆（3 個測試，全過）
- [x] 4.2 `ScrapeRunRepositoryTest`——確認過這個 repository 目前沒有既有測試檔，
      不新增（跟 tasks.md 原本記錄的判斷一致）
- [x] 4.3 `./gradlew :collector:test`、`:worker:test` 全過
