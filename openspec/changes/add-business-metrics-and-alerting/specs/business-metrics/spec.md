# Spec: business-metrics

## ADDED Requirements

### Requirement: 靜默失敗可被偵測
系統 SHALL 記錄每個來源每輪掃描實際發現的職缺數，使「外部平台回應成功但未回傳任何資料」
這種不產生例外、不產生 error log、不影響任何基礎設施指標的失效模式可被偵測。

#### Scenario: 掃描成功但零發現
- **WHEN** 某來源的 list API 回傳 HTTP 200 且職缺陣列為空，掃描流程正常結束
- **THEN** `jobradar_scan_total{result="success"}` 遞增，且 `jobradar_jobs_discovered_total`
  不變，兩者的差異足以判定系統已停止產出價值

### Requirement: 掃描結果可觀測
`ScanService` SHALL 對每一輪掃描記錄結果、發現筆數與耗時，並以 `source` 區分。

#### Scenario: 掃描成功
- **WHEN** `ScanService.runScan()` 正常完成
- **THEN** `jobradar_scan_total{source, result="success"}` 遞增，
  `jobradar_jobs_discovered_total{source}` 增加該輪發現筆數，
  `jobradar_scan_duration_seconds` 記錄耗時

#### Scenario: 掃描失敗
- **WHEN** `ScanService.runScan()` 的 scraper 拋出例外
- **THEN** `jobradar_scan_total{source, result="failure"}` 遞增

### Requirement: 外部平台限速可觀測
爬蟲遭遇 HTTP 429 而重試時 SHALL 記錄計數，不得僅存在於 log 文字中。

#### Scenario: 遭遇限速
- **WHEN** `YouratorListScraper.fetchPage()` 收到 429 並進入重試
- **THEN** `jobradar_scrape_retry_total{source="yourator", reason="rate_limited"}` 遞增

### Requirement: 解析降級可觀測
Parser 遇到格式異常時回傳 null 的優雅降級行為 SHALL 被計數。

#### Scenario: 解析失敗但不中斷流程
- **WHEN** parser 因來源格式變動而無法解析，依既有設計回傳 null 並記錄 warning log
- **THEN** `jobradar_parse_total{source, result="failure"}` 遞增，
  使這個「安靜降級」的行為可被聚合與告警

### Requirement: 端到端 pipeline 延遲可量測
系統 SHALL 在 Discord 推播成功時，以 `JobEventEnvelope.scrapedAt` 為基準記錄端到端延遲，
作為 SLO-1 的 SLI 來源。

#### Scenario: 推播成功時記錄延遲
- **WHEN** `DiscordNotifier.onEvent()` 成功推播一則 NEW 事件
- **THEN** `jobradar_pipeline_latency_seconds` 記錄 `now - event.scrapedAt()`

#### Scenario: SLO 門檻為精確計數而非估算
- **WHEN** 檢視 `jobradar_pipeline_latency_seconds` 的 histogram bucket 設定
- **THEN** 存在一個邊界恰好位於 5 分鐘的 bucket，使 SLI 可由
  「bucket 內樣本數 ÷ 總樣本數」精確計算，而不需 `histogram_quantile()` 估算

#### Scenario: 不依來源切分
- **WHEN** 檢視 `jobradar_pipeline_latency_seconds` 的 label
- **THEN** 不含 `source`——SLO-1 描述 pipeline 整體健康，分來源比較改查
  `jobradar_scan_duration_seconds`

### Requirement: Discord 推播成敗可觀測
`DiscordNotifier` SHALL 記錄推播成功與失敗次數。

#### Scenario: 推播失敗被計數
- **WHEN** Discord webhook 回傳錯誤
- **THEN** `jobradar_notification_total{result="failure"}` 遞增

### Requirement: 指標 label 基數受控
所有 metric label 的值域 MUST 有界且極小。

#### Scenario: 禁止的 label
- **WHEN** 檢視所有新增指標的 label
- **THEN** 不含 `query_keyword`（使用者可從前端自由新增，值域無界）、
  `sourceJobId`、`url`、`title`，亦不含任何例外訊息內容

#### Scenario: 失敗原因查詢途徑
- **WHEN** 需要知道某次失敗的具體原因
- **THEN** 查詢 Loki 的結構化 log，而非 metrics——metrics 回答「發生了幾次」，
  logs 回答「為什麼」

### Requirement: 兩條取得路徑的一致性
以應用內埋點（Path B）取得的指標，與 `add-platform-observability` 由 `scrape_runs`
聚合而來（Path A）的同義指標 SHALL 數值一致。

#### Scenario: 交叉驗證
- **WHEN** 比對 `jobradar_scan_total` 推導的成功率與 Path A 的 `scrape_runs` 聚合成功率
- **THEN** 兩者一致；若不一致，代表其中一方存在邏輯錯誤，MUST 查明原因而非任選一方採信
