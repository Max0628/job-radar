# Spec: scraping-pipeline

## ADDED Requirements

### Requirement: 排程增量掃描
系統 SHALL 依 `search_queries` 設定的頻率（每 2 小時 + jitter）自動觸發 Yourator 掃描，
以更新時間排序翻頁，翻到職缺更新時間早於上次掃描游標即停止（early termination）。

#### Scenario: 新職缺在下一輪出現
- **WHEN** Yourator 上出現符合關鍵字的新職缺
- **THEN** 下一輪掃描後該職缺存在於 `jobs` 表，且 `scrape_runs` 有該輪記錄

#### Scenario: 無新職缺時的低成本掃描
- **WHEN** 兩輪掃描之間平台無任何更新
- **THEN** 該輪只請求第一頁 list 即終止，不發任何 detail 請求

### Requirement: 兩段式抓取與限速
Detail fetcher SHALL 只對資料庫中不存在的職缺發出 detail 請求，且對同一來源
並發 ≤ 2、間隔 ≥ 1 秒，收到 429 時退避。

#### Scenario: 已知職缺不重抓
- **WHEN** `jobs.discovered` 收到一筆 `(source, source_job_id)` 已存在於 `jobs` 表的訊息
- **THEN** 不發出 detail 請求，該訊息正常 ack

### Requirement: 冪等寫入
Normalizer SHALL 以 `(source, source_job_id)` 為唯一鍵 upsert `jobs`，快照對
`(source, source_job_id, scraped_at)` insert-ignore；重複消費同一訊息不得產生第二筆
資料或第二個 NEW 事件。

#### Scenario: 訊息重放安全
- **WHEN** 手動重放一則已處理過的 `jobs.raw` 訊息
- **THEN** `jobs`、`job_snapshots`、`jobs.events` 均無新增

### Requirement: 失敗隔離
單筆訊息處理失敗 SHALL 重試 3 次（指數退避），耗盡後進入 `<topic>.dlq`，
且資料庫不留下部分寫入。

#### Scenario: 毒訊息不阻塞管線
- **WHEN** 一筆 payload 無法解析的訊息進入 `jobs.raw`
- **THEN** 該訊息最終位於 `jobs.raw.dlq`，其後的訊息正常處理
