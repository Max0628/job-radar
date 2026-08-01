## Context

`ScanScheduler.tick()` 原本單執行緒依序處理每個到期的 `search_queries`；每個查詢只有一種
掃描行為（翻到 `hasMore=false`/`total_entries` 達標，或撞到既有的兩個安全網：時間預算、
分頁卡住偵測）。`MAX_RETRY`/`MAX_SCAN_DURATION`/退避公式逐字重複在
`YouratorListScraper`/`CakeResumeListScraper` 兩個檔案裡。`docs/architecture.md` D6/D23
已經定案要改成什麼樣子，這份文件記錄實際落地時的技術決策。

## Goals / Non-Goals

**Goals**
- 三來源到期查詢平行執行，不循序等待
- 淺掃/深掃收斂成同一套邏輯，一個「提早停止」開關，不維護兩條獨立排程
- 深掃可跨 tick 接續翻頁，不用每次從第 1 頁重來
- 共用的重試/逾時常數抽到一處，支援之後的來源覆寫

**Non-Goals**
- 不把 `@Scheduled(fixedDelay=...)` 輪詢改成 cron 固定整點觸發（見 `architecture.md`
  D6「這次刻意不做」段落，跟這次核心目標沒有强依賴，留給更小的獨立 change）
- 不處理 104 這個新來源本身（另開 change）
- 不做 Discord 掃描回報（D21，另開 change）

## Decisions

**D1（本 change 內部編號，非 architecture.md 的 D 系列）：並行執行用虛擬執行緒，不用
`@Async`/執行緒池**
`ScanScheduler.tick()` 改用 `Executors.newVirtualThreadPerTaskExecutor()` + try-with-
resources，`close()` 會等所有送出的任務跑完才返回，維持 `tick()` 整體同步、不會跟下一次
排程觸發重疊。跟 `spring.threads.virtual.enabled=true` 的既有技術路線一致。
- 被否決：Spring `@Async` + 固定執行緒池——需要額外設定執行緒池大小，虛擬執行緒不用煩惱
  這個，且量級小（現階段最多 3 個來源同時），不需要限流

**D2：早停判斷用 predicate 注入，不是讓 scraper 直接查資料庫**
原計畫想讓 scraper 直接依賴 worker 模組現有的 `JobExistenceRepository`，實作時發現兩個
問題：`JobListScraper` 介面文件明講「不查資料庫」；`collector` 模組（`build.gradle.kts`）
本來就不依賴 `worker` 模組，無法直接 import。改為：`JobListScraper.scan()` 新增
`Predicate<Set<String>> pageIsFullyKnown` 參數，由 `ScanService` 組出這個 predicate
（背後是新建的 collector 本地版 `JobExistenceRepository`，跟 worker 那份邏輯相同、刻意的
小型重複——share 一個模組給這麼小的東西不划算）。scraper 本身完全不碰資料庫，維持既有的
架構邊界。
- 被否決：讓 `collector` 依賴 `worker` 模組（打破 D7 三個獨立部署單元的邊界）；改寫
  `JobListScraper` 文件放寬「不查資料庫」這條規則（比新增一個小 repository 的代價更大，
  且這條規則背後的理由——scraper 只做發現、不做其他職責——依然成立）

**D3：早停只比對「存不存在」，不比對內容有沒有變**
內容比對需要完整 detail payload，`needsDetail=true` 的來源（Yourator，之後的 104）在
list 階段根本拿不到，判斷不了。技術債，記錄在 `JobExistenceRepository` 類別註解裡。

**D4：深掃接續游標（`last_page_scanned`）只有深掃模式讀寫，淺掃完全不碰**
`ScrapeCursorRepository.updateAfterScan()` 拆成 `updateAfterLightScan()`（只更新
`last_scanned_at`）/`updateAfterDeepScan()`（依 `reachedEnd` 決定要歸零還是存接續頁碼）。
如果淺掃也寫這個欄位，會把深掃的接續進度覆蓋掉。

**D5：`reachedEnd`/`nextPageToResume` 放進 `ScanResult`，由 scraper 誠實回報，
`ScanService` 只負責依此寫游標**
分頁卡住安全網觸發時視為 `reachedEnd=true`（等同翻完，不值得原地接續）；只有被時間預算
打斷才是 `false`。淺掃的這兩個欄位值不影響行為（`ScanService` 只在 `deepMode=true` 時
才讀取），但 scraper 仍誠實計算，不特殊處理淺掃分支。

## Risks / Trade-offs

- **[風險] 早停用「存不存在」而非「內容有沒有變」，可能漏掉已存在但內容已更新的職缺**
  → 緩解：深掃每隔設定時數（預設 24h）會完整重新掃過一輪，遺漏的更新最晚在下次深掃補上；
  這是刻意的簡化，不是疏漏
- **[風險] Yourator 的 list 排序是相關性、非時間**，早停可能停在「還有新職缺在後面頁」的
  位置 → 緩解：`architecture.md` 待決事項已記錄，屬於已知限制，深掃仍會補上
- **[風險] 虛擬執行緒平行執行讓 collector pod 記憶體峰值變高** → 已於架構討論階段確認
  collector pod 的瓶頸是 CPU（0.5 vCPU limit）而非記憶體（640Mi limit，VM 層級有餘裕），
  CPU 超限只會節流變慢、不會 OOMKill；記憶體真的偏緊時可以直接調高 pod limit，成本低

## Migration Plan

- Flyway migration `V11__add_deep_scan_cursor.sql`：`ALTER TABLE scrape_cursors ADD COLUMN
  last_deep_scan_completed_at TIMESTAMPTZ`（nullable，無需 backfill，既有資料的深掃視為
  「從未做過」，下次排程自然觸發一次深掃）
- 無需 rollback 特殊處理：新欄位為 nullable，且沒有既有程式碼讀取它，可安全新增

## Open Questions

（無——實作階段已完成，過程中的技術決策都已收斂進「Decisions」章節）
