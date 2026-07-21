# Spec: cakeresume-adapter

CakeResume 平台的爬蟲適配器，負責發現職缺。

## ADDED Requirements

### Requirement: CakeResume list scraper 能發現職缺

系統 SHALL 支持定期呼叫 CakeResume 的 search API，發現符合條件的職缺，並發送 DiscoveredEnvelope。

#### Scenario: 成功爬取職缺列表
- **WHEN** Scheduler 觸發 CakeResumeCollector（根據 search_queries 表的設定）
- **THEN** Collector 呼叫 `POST https://api.cake.me/api/client/v1/jobs/search`
  - 傳入 query、filters（location）、page、sort_by
  - 回應包含 data 陣列（職缺列表）
- **AND** 為每筆職缺發送一筆 DiscoveredEnvelope 到 `jobs.discovered` topic
  - envelope.source = "cakeresume"
  - envelope.sourceJobId = 職缺的 id
  - envelope.needsDetail = false（CakeResume search 已完整）
  - envelope.payload = search 結果的單筆職缺物件

#### Scenario: 分頁處理
- **WHEN** Collector 發現 response 的 total_entries > 當前頁的職缺數
- **THEN** Collector 翻下一頁，持續發送 DiscoveredEnvelope，直到達到 query.maxPages

#### Scenario: 低並發 + 間隔禮貌爬蟲
- **WHEN** Collector 對 CakeResume 連續發送請求
- **THEN** 同一來源（CakeResume）的並發 ≤ 2，每兩個請求間間隔 ≥ 1 秒
- **AND** 若收到 429（Too Many Requests），退避後重試（最多 3 次）

#### Scenario: 失敗重試 + 記錄
- **WHEN** CakeResume API 回應非 200（如 500、timeout）
- **THEN** Collector 重試最多 3 次，每次退避時間遞增
- **AND** 若最終失敗，寫入 scrape_runs.status = 'FAILED'、錯誤訊息
- **AND** Collector 程序正常結束（不 crash），下次排程再試

### Requirement: search_queries 表驅動 CakeResume 爬蟲

系統 SHALL 根據 search_queries 表的設定決定爬哪些關鍵字 + 地區組合。

#### Scenario: 多組關鍵字並行爬取
- **WHEN** search_queries 表有多筆 source='cakeresume' 的記錄
  - 例：(cakeresume, "後端工程師", "Taipei", 120 分鐘)
  - 例：(cakeresume, "DevOps", "Taipei", 120 分鐘)
- **THEN** Scheduler 為每筆記錄觸發一次 CakeResumeCollector 任務
- **AND** 各任務間隔 ≥ 1 秒（禮貌規則）

#### Scenario: 排程間隔
- **WHEN** search_queries.intervalMinutes = 120（對應 CakeResume 行）
- **THEN** Scheduler 每 2 小時 + jitter 觸發一次該 query 的爬蟲

### Requirement: 爬蟲結果記錄

系統 SHALL 記錄每輪爬蟲的執行結果到 scrape_runs 表。

#### Scenario: 成功爬取的記錄
- **WHEN** CakeResume Collector 完成一輪爬蟲
- **THEN** 寫入 scrape_runs：
  - source = 'cakeresume'
  - query_id = 對應的 search_query 記錄 id
  - started_at = 開始時間
  - ended_at = 結束時間
  - jobs_discovered = 這輪發現的職缺數
  - status = 'SUCCESS' 或 'PARTIAL'（部分頁面失敗）

#### Scenario: 失敗記錄
- **WHEN** CakeResume API 連續失敗 3 次以上
- **THEN** 寫入 scrape_runs.status = 'FAILED'、error_message
- **AND** 發送告警（DLQ depth 或 alert 機制）
