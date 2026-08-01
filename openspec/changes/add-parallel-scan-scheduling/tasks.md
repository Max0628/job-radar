> 補寫紀錄：以下任務實際上是先實作、後補這份 tasks.md（見 proposal.md「補充說明」），
> 全部已完成並通過 `./gradlew :collector:test`，勾選狀態反映真實現況。

## 1. 共用常數抽離（D23）

- [x] 1.1 `CollectorScanProperties` 新增 `maxRetry`/`maxScanDurationMinutes`/
      `retryBackoffBaseMillis` 全域欄位 + `sources` per-source 覆寫 map
- [x] 1.2 `application.yml` 補上對應全域預設值（不改變既有行為）
- [x] 1.3 `YouratorListScraper`/`CakeResumeListScraper` 刪除各自重複的
      `MAX_RETRY`/`MAX_SCAN_DURATION` 常數與退避公式，改讀共用設定
- [x] 1.4 更新 `YouratorListScraperTest`/`CakeResumeListScraperTest`/
      `ScanSchedulerTest` 的 `CollectorScanProperties` 建構呼叫
- [x] 1.5 `./gradlew :collector:test` 全過，確認純重構未改變既有行為

## 2. 並行執行

- [x] 2.1 `ScanScheduler.tick()` 改用 `Executors.newVirtualThreadPerTaskExecutor()`
      平行送出到期查詢，維持既有 per-query try/catch 隔離

## 3. 淺掃/深掃合併（D6）

- [x] 3.1 Flyway migration `V11__add_deep_scan_cursor.sql`：`scrape_cursors` 新增
      `last_deep_scan_completed_at`
- [x] 3.2 新建 `collector` 本地版 `JobExistenceRepository`（不依賴 worker 模組，
      見 design.md D2）
- [x] 3.3 `ScanResult` 新增 `reachedEnd`/`nextPageToResume` 欄位
- [x] 3.4 `JobListScraper` 介面簽名改為
      `scan(SearchQuery, boolean deepMode, int startPage, Predicate<Set<String>>
      pageIsFullyKnown)`
- [x] 3.5 `ScrapeCursorRepository` 新增 `findLastPageScanned`/
      `findLastDeepScanCompletedAt`，`updateAfterScan` 拆成
      `updateAfterLightScan`/`updateAfterDeepScan`
- [x] 3.6 `CollectorScanProperties` 新增 `deepScanIntervalHours`/
      `deepScanMaxDurationMinutes`，`maxScanDurationFor` 改為依 `deepMode` 分流
- [x] 3.7 `ScanService` 新增模式判斷邏輯（`isDeepScanDue`）、組出早停 predicate、
      依模式呼叫對應的 cursor repository 方法
- [x] 3.8 `YouratorListScraper`/`CakeResumeListScraper` 實作早停判斷（每頁比對
      `pageIsFullyKnown`）與深掃接續（從 `startPage` 開始），所有 return 路徑
      補上 `reachedEnd`/`nextPageToResume`
- [x] 3.9 更新 `ScanServiceTest` 建構子呼叫、新增早停/深掃相關測試

## 4. 測試

- [x] 4.1 `YouratorListScraperTest` 新增：整頁已知時提早停止、深掃從 startPage
      接續、深掃被時間預算打斷回報 `reachedEnd=false`
- [x] 4.2 `ScanServiceTest` 新增：淺掃只更新 `last_scanned_at`、深掃翻完歸零並記錄
      完成時間、深掃被打斷存接續頁碼且不標記完成
- [x] 4.3 `./gradlew :collector:test` 全過（既有 + 新增測試，共 12 個測試方法橫跨
      `YouratorListScraperTest`/`CakeResumeListScraperTest`/`ScanServiceTest`/
      `ScanSchedulerTest`）
- [x] 4.4 未對真實 Yourator/CakeResume API 做端到端測試——沿用既有的
      `MockRestServiceServer` + fixture 模式；真實環境驗證需另外手動、謹慎進行
      （見 `docs/architecture.md` 的先例：手動撥動 `scrape_cursors` 觸發、全程盯著看）

## 5. 補寫 SDD 文件（本次事後補做）

- [x] 5.1 補開 `add-parallel-scan-scheduling` change（`openspec new change`）
- [x] 5.2 補寫 `proposal.md`（引用 `docs/architecture.md` D6/D19/D23，不重述理由）
- [x] 5.3 補寫 `design.md`（含實作過程中偏離原計畫的技術決策，特別是早停判斷改用
      predicate 注入而非直接依賴 worker 模組的 repository）
- [x] 5.4 補寫 `specs/scan-scheduling/spec.md`
- [x] 5.5 補寫本檔案（`tasks.md`），如實標記「先做後補」
