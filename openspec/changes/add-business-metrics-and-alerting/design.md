## Context

`add-platform-observability` 完成後，以下指標已可取得且**不需要重複埋點**：

| 來源 | 提供的指標 |
|---|---|
| Spring Boot Actuator | JVM heap／GC／thread、`http_server_requests_seconds`（server 端 RED） |
| spring-kafka + Micrometer | producer／consumer 速率、client 端 consumer lag |
| HikariCP | 連線池使用狀況 |
| resilience4j | `YouratorDetailScraper` 的 `@Retry(name="yourator")` 重試次數與成敗 |
| kafka-exporter | broker 端 consumer lag、DLQ topic offset |
| postgres_exporter（Path A） | 各來源掃描次數／成功數／發現筆數／最後成功時間 |
| ingress-nginx | frontend 的真實使用者 RED |

本 change 只補上「上表全部加起來仍然量不到」的部分。實際的免費指標清單以
`add-platform-observability` tasks 1.7 的產出為準，埋點前必須先核對，避免重複造輪子。

程式碼現況（已逐一確認）：

- `ScanService.runScan()` 已完整寫入 `scrape_runs`，包含 `pagesScanned`、
  `result.discovered().size()`、成功／失敗狀態
- `JobEventEnvelope` 帶有 `scrapedAt`（`Instant`），一路從 `DiscoveredEnvelope` 傳遞到 notifier
- `DiscordNotifier.onEvent()` 對 `restClient.post()` 沒有 try/catch，例外向上拋給
  `DefaultErrorHandler`，經 `FixedBackOff(1000L, 3L)` 重試後由 `DeadLetterPublishingRecoverer`
  送進 `jobs.events.dlq`（`KafkaConsumerConfig`）
- `DiscordNotifier` 建構子使用 `RestClient.builder()` 靜態工廠；`YouratorListScraper` 使用
  注入的 `RestClient.Builder`
- `YouratorListScraper.fetchPage()` 有手刻的 429 重試迴圈（`MAX_RETRY = 3`），未使用 resilience4j
- 三個服務的 `logback-spring.xml` 皆使用 `LogstashEncoder`，輸出結構化 JSON

## Goals / Non-Goals

**Goals:**
- 偵測爬蟲靜默失敗（回 200 但沒有資料）
- 量測端到端 pipeline 延遲，作為 SLO-1 的 SLI
- 讓 Discord 推播的成敗可觀測，且不改變既有的 DLQ 錯誤處理語意
- 建立可被單元測試驗證的告警規則
- 讓 dashboard 與告警規則全部進版控，符合 GitOps 原則
- 在本機完成所有驗證後，只推送一次觸發 CI

**Non-Goals:**
- 不追求 SLO 數值的「正確」——第一版目標值是判斷依據，觀察一段時間後可調整
- 不做告警的自動修復（auto-remediation）
- 不埋沒有消費者的指標

## Decisions

### 指標命名與 Micrometer 的轉換規則

Micrometer 在程式碼中使用點號命名（`jobradar.scan`），Prometheus registry 匯出時會自動
轉為底線並依 meter 型別加上後綴（counter 加 `_total`、timer 產生 `_seconds_count` /
`_seconds_sum` / `_seconds_bucket`）。**程式碼裡寫點號、PromQL 裡查底線**，這個轉換是自動的，
不需要（也不應該）在程式碼裡寫底線。

| 程式碼中的 meter 名稱 | 型別 | Label | Prometheus 中查詢的名稱 |
|---|---|---|---|
| `jobradar.scan` | Counter | `source`, `result` | `jobradar_scan_total` |
| `jobradar.scan.duration` | Timer | `source` | `jobradar_scan_duration_seconds_*` |
| `jobradar.jobs.discovered` | Counter | `source` | `jobradar_jobs_discovered_total` |
| `jobradar.scrape.retry` | Counter | `source`, `reason` | `jobradar_scrape_retry_total` |
| `jobradar.parse` | Counter | `source`, `result` | `jobradar_parse_total` |
| `jobradar.events.published` | Counter | `source`, `type` | `jobradar_events_published_total` |
| `jobradar.notification` | Counter | `result` | `jobradar_notification_total` |
| `jobradar.pipeline.latency` | Timer | （無） | `jobradar_pipeline_latency_seconds_*` |

### Label 基數規則（硬性）

所有 label 的值域 MUST 有界且極小：

- ✅ `source`：`yourator`／`cakeresume`，由程式碼中的 adapter 決定
- ✅ `result`：`success`／`failure`
- ✅ `type`：`NEW`／`CHANGED`
- ✅ `reason`：預先列舉的固定字串（如 `rate_limited`／`timeout`）
- ❌ **`query_keyword`**：由使用者從前端的 search_queries 配置台自由新增，值域無界
- ❌ **`sourceJobId`／`url`／`title`**：每筆職缺一個值，會產生數萬條 time series

異常訊息 MUST NOT 作為 label（`e.getMessage()` 的內容不可預測）。需要知道失敗原因時
查 Loki 的結構化 log，不查 metrics。這是 metrics 與 logs 的職責分界：
**metrics 回答「發生了幾次」，logs 回答「為什麼」。**

### `jobradar.pipeline.latency` 是本 change 的核心指標

在 `DiscordNotifier.onEvent()` 推播成功後記錄 `Duration.between(event.scrapedAt(), Instant.now())`。

這條指標同時是 SLO-1 的 SLI，因此 Timer MUST 設定明確的 SLO 邊界（Micrometer 的
`serviceLevelObjectives`），在 5 分鐘處產生一個 histogram bucket。這樣 SLI 就是一個
單純的除法：

```
落在 5 分鐘 bucket 內的樣本數 / 樣本總數
```

而不需要用 `histogram_quantile()` 去估算分位數。**分位數估算的精度取決於 bucket 配置，
用它來判定 SLO 達標與否會引入不必要的誤差**；直接在 SLO 門檻處設一個 bucket 邊界，
得到的是精確計數。

此指標刻意不加 `source` label：SLO-1 描述的是 pipeline 這個整體的健康，不是個別來源的。
需要分來源比較時查 `jobradar.scan.duration`。

### `DiscordNotifier`：計數後必須重新拋出例外

加入成敗計數需要 try/catch，但 catch 區塊 MUST 在計數後重新拋出原例外。

現行行為依賴例外向上傳播至 `DefaultErrorHandler`，才會觸發三次重試與 DLQ 投遞
（`KafkaConsumerConfig.buildFactory`）。若為了計數而吞掉例外，訊息會被視為處理成功並
commit offset，**推播失敗的職缺將永久遺失，且 DLQ 永遠是空的**——反而讓可觀測性倒退，
同時破壞 D5 的 at-least-once 保證。

這是本 change 最容易寫錯、且錯了之後最難察覺的一處，tasks 中安排獨立的測試驗證。

### `DiscordNotifier` 改為注入 `RestClient.Builder`

與 `YouratorListScraper` 一致。注入的 builder 帶有 Spring Boot 自動組態的 observability
支援，改動後 Discord 呼叫會自動獲得 client 端 HTTP 指標（`http.client.requests`），
且在 `add-distributed-tracing` 上線後會自動產生 span，不需要再改一次。

行為風險評估：自動組態可能加入 observation interceptor，但不改變請求內容或錯誤語意。
`CakeResumeListScraper` 與 `YouratorDetailScraper` 的 client 取得方式尚未逐一確認，
tasks 中安排統一檢查。

### 靜默失敗偵測不屬於 SLO，而是獨立的高優先告警

「yourator 過去 6 小時發現 0 筆職缺」描述的是「系統是否還在運作」，不是「服務品質水準」。
把它併入 SLO 會讓 error budget 的語意混亂——一個完全停止運作的系統，其 error budget
消耗速度應該是「立即耗盡」而不是「按比例扣減」。

因此它是一條獨立的 `severity: critical` 告警，不佔用 error budget。

門檻設為 6 小時的理由：`search_queries` 的 `intervalMinutes` 目前為數小時級，且
`ScanScheduler` 限制只在台灣時間 08:00–23:00 掃描（`isWithinActiveHours`），
低於 6 小時容易在正常的排程間隔中誤報。此告警 MUST 只在活躍時段評估，
否則每天凌晨都會誤報一次。

### SLO 定義

**SLO-1｜Pipeline 延遲**
> 99% 的職缺事件，從 `scrapedAt` 到 Discord 推播成功，耗時 < 5 分鐘

SLI 來源：`jobradar_pipeline_latency_seconds` 的 5 分鐘 bucket 計數 ÷ 總計數。

門檻選擇：這條 pipeline 正常情況下是秒級（三跳 Kafka + 一次 HTTP）。5 分鐘寬鬆到
幾乎不可能因正常波動而違反，一旦違反必然代表真的塞住了——consumer lag 累積、
外部 API 異常緩慢、或 Discord 限流。寬門檻換取的是**近乎零誤報**，這對一個由單人
維護、告警送到 Discord 的系統是正確的取捨。

**SLO-2｜掃描成功率**
> 每個來源，每日應執行的掃描中成功完成的比例 ≥ 95%

SLI 來源：`jobradar_scan_total{result="success"} / jobradar_scan_total`（Path B），
並以 Path A 的 `scrape_runs` 聚合值交叉驗證。

**SLO-2 在 `add-platform-observability` 完成時即已具備資料基礎**（Path A），
不需等待本 change 的埋點上線。

門檻選擇：爬取對象是不受控的外部平台，429、暫時性 5xx、改版都會發生。95% 允許
合理的失敗率，同時對「持續性失敗」仍然敏感。

**刻意不定義的 SLO：「新職缺 N 小時內送達」**

從職缺在平台上發布到使用者收到通知的總延遲，主要由 `search_queries.intervalMinutes`
與 `ScanScheduler` 的活躍時段設定決定——那是**設計決策，不是故障**。把它寫成 SLO
只會在調整掃描頻率時產生大量假違約。

技術上這條 SLO 未來是可行的：`add-job-posted-date` 已完成，`jobs.posted_at` 存有平台
回報的真實發布時間（CakeResume 366/366、Yourator 211/373 回填成功）。但該欄位刻意
不流經 `JobEventEnvelope`（見 `add-job-posted-date/design.md`），且 DB 也沒有記錄推播
時間，因此目前兩端都拿不到。若未來要做，需先決定是遞增 envelope 的 `schemaVersion`
把 `postedAt` 帶進事件，或在 DB 記錄推播時間戳——兩者都超出本 change 範圍。

### Error budget 的用途是資源分配，不是告警門檻

以 SLO-2 為例：設每月總掃描次數為 N，error budget = N × 5%。

這個數字的用途**不是**拿來設定告警閾值，而是作為工程決策的仲裁：

> 若某月消耗超過 error budget，代表爬蟲穩定性已經需要投入工程時間修復，
> 而不是繼續新增來源或功能。

這是 error budget 在真實工程組織中的角色——它終結「要做新功能還是修穩定性」的爭論，
把它變成一個由數據決定的問題。實際的 N 取決於 `search_queries` 的設定，
在驗收階段以實測值計算。

### 告警清單與嚴重度

| 告警 | 層級 | severity | 依據 |
|---|---|---|---|
| 某來源 6h 內發現職缺數為 0（僅活躍時段評估） | Business | critical | 靜默失敗，系統已停止產出價值 |
| 任一 DLQ topic 深度 > 0 | Business | critical | 有資料實際遺失中 |
| SLO-1 error budget 快速消耗 | SLO | warning | 見下方 burn rate |
| Discord 推播失敗率 > 10%（15m） | Business | warning | 最後一哩路失效 |
| 掃描失敗率 > 5%（1h，分來源） | Business | warning | SLO-2 相關 |
| consumer lag 持續成長 15 分鐘（broker 端） | Platform | warning | 使用 kafka-exporter 數據 |
| PostgreSQL 連線數 > 80% | Platform | warning | |
| Longhorn volume 使用率 > 85% | Infra | warning | 已實際踩過 |
| 憑證 30 天內到期 | Platform | info | |
| ArgoCD app OutOfSync > 15 分鐘 | GitOps | info | |

原則：**對症狀告警，不對原因告警。** 「CPU > 80%」不在清單中——它可能完全無害，
而真正有害的情況會透過延遲或失敗率呈現。每一條告警都必須能回答
「收到之後我現在要做什麼」；答不出來的，它應該是 dashboard 上的一個數字，不是告警。

### Burn rate 告警採簡化版雙視窗

完整的 multi-window multi-burn-rate（Google SRE book）對單人系統過重。採用簡化版：

- **快速燃燒**：1 小時視窗內的消耗速率若持續下去會在數天內耗盡當月 budget → `warning`
- **緩慢燃燒**：6 小時視窗的長期趨勢 → `info`

目的是理解 burn rate 的計算方式（消耗速率 vs 剩餘額度），而不是建立完整的告警分層。
具體倍率在實作時依實測的 N 計算。

### 告警規則必須有單元測試

以 `promtool test rules` 為每一條告警撰寫測試：餵入合成的 time series，
斷言告警在預期時間點進入 firing、在不該觸發時保持靜默。

理由：**告警規則平常不會執行，寫錯了不會有任何徵兆，直到真的出事那天它沒有響。**
這是可觀測性系統本身最大的盲區。靜默失敗告警尤其必須測試——它正是為了捕捉
「什麼都沒發生」而存在，最難用手動方式驗證。

### Dead man's switch

kube-prometheus-stack 內建一條恆常觸發的 `Watchdog` 告警，設計用途是路由到一個
「收不到就代表監控系統本身掛了」的接收端。本 change 確認其現行路由狀態並明確處置：
要嘛正確接上外部檢查，要嘛明確記錄為刻意忽略。

若監控系統自己死了而無人知曉，上面所有告警都等於不存在。

### Dashboard as code

Grafana（kube-prometheus-stack bundle）的 sidecar 會自動載入帶有
`grafana_dashboard: "1"` label 的 ConfigMap。因此 dashboard JSON 可以進 `k8s` repo、
由 ArgoCD 同步、可 code review、可 `git revert`。

工作流程：**在 Grafana UI 調整至滿意 → export JSON → 寫進 git → 由 sidecar 載入**。
不允許在 UI 上手改後不進 git，那會造成 drift（`homelab-infra/ARCHITECTURE.md`「GitOps 原則」）。

本 change 只自建**一個** dashboard：pipeline 漏斗圖，依序呈現
`scan → discovered → raw → normalized → events → notified` 各階段的量，讓任何一段的
掉量一眼可見。其餘（JVM、Kafka、PostgreSQL、Node）一律使用社群現成 dashboard，
只是把它們的 JSON 收進版控——這也是真實公司的常見做法，沒有必要自己重畫。

### 與 `add-distributed-tracing` 的銜接

本 change 不實作 tracing，但埋點與 log 設計需預留空間，避免之後回頭改兩次：

- 使用注入的 `RestClient.Builder`（見上），tracing 上線後自動產生 client span
- 使用 Micrometer 的 `Timer` 而非手算時間差，tracing 上線後可與 span 對應
- `logback-spring.xml` 已使用 `LogstashEncoder`，Micrometer Tracing 注入 MDC 的
  `traceId`／`spanId` 會自動出現在 JSON log 中，本 change 不需要預先改動

## Risks / Trade-offs

- **[Risk] `DiscordNotifier` 加入 try/catch 後忘記重新拋出例外，導致失敗訊息被靜默丟棄、
  DLQ 永遠為空。** 這會同時破壞 at-least-once 保證（D5）與可觀測性，且**表面上看起來
  一切正常**。→ 安排獨立測試：模擬 webhook 失敗，斷言例外仍向上傳播。
- **[Risk] 埋點與 Path A 的數值長期不一致。** → 驗收時交叉比對；不一致代表其中一方有邏輯
  錯誤，必須查明而非選一個相信。
- **[Risk] SLO-1 門檻寬鬆到失去意義。** 5 分鐘對秒級 pipeline 而言極寬。→ 這是刻意的，
  第一版優先確保零誤報；累積一個月實測分佈後再收緊。
- **[Risk] 靜默失敗告警在非活躍時段誤報。** `ScanScheduler` 只在 08:00–23:00 掃描，
  凌晨必然「0 筆發現」。→ 告警規則必須帶時段條件，且此情境列入 `promtool` 測試案例。
- **[Trade-off] 只推送一次 CI 意味著所有 Java 變更必須一起驗證完成。** → 以
  `SimpleMeterRegistry` 單元測試 + 本機 `bootRun` + `curl /actuator/prometheus` 三層驗證，
  在推送前確認完畢。
- **[Trade-off] 不使用 SLO 專用工具，手寫 burn rate 規則較繁瑣且容易算錯。** → 接受，
  理解計算方式是本 change 的目的之一；以 `promtool test rules` 驗證正確性。

## 驗證策略

Java 變更會觸發 GitLab CI（耗時長），因此**全部驗證在推送前於本機完成，只推一次**。

| 方法 | 驗證什麼 | 速度 |
|---|---|---|
| `SimpleMeterRegistry` 單元測試 | counter 在正確時機遞增、label 正確、meter 名稱正確 | 秒級 |
| 例外傳播測試 | `DiscordNotifier` 計數後例外仍向上拋 | 秒級 |
| `./gradlew build` | CI 會做的編譯與測試先在本機跑過 | 分鐘級 |
| 本機 `bootRun` + `curl /actuator/prometheus` | 指標真的匯出、名稱與 label 如預期、無非預期高基數 | 分鐘級 |
| `promtool check rules` | 告警規則語法正確 | 秒級 |
| `promtool test rules` | 餵合成資料驗證告警確實會／不會觸發 | 秒級 |
| `kubectl apply --dry-run=server` | PrometheusRule／ConfigMap schema 正確 | 秒級 |

`k8s` repo 的部分（PrometheusRule、dashboard ConfigMap、Alertmanager 設定）不經過 CI，
可以獨立於 Java 變更先行推送與迭代。

## Migration Plan

1. `k8s` repo 先行：以 Path A 既有指標撰寫 SLO-2 與靜默失敗告警，附 `promtool` 測試，
   推送並實際驗證能送達 Discord（此時完全不碰 Java code）
2. 刻意觸發一次告警（例如暫時把靜默失敗門檻改為極短）確認端到端通知路徑真的可用——
   **未經實際觸發驗證的告警等於不存在**
3. 本機完成所有 Java 埋點與測試
4. 單次 commit + push，CI 執行一次
5. 部署後確認新指標出現，與 Path A 交叉驗證
6. 補上依賴 Path B 的告警（SLO-1、推播失敗率）與 pipeline 漏斗 dashboard
7. 觀察一個月後檢視 SLO 門檻是否需要調整，並實際計算一次 error budget 消耗

回退：Java 變更 `git revert` 後重跑 CI；`k8s` repo 變更 `git revert` 後由 ArgoCD 自動同步。
埋點為純新增、不改變業務邏輯（唯一的行為面改動是 `DiscordNotifier` 的 client 取得方式），
回退風險低。
