## Why

`add-platform-observability` 完成後，基礎設施層、平台層、應用層的指標都收得到了。但這些指標有一個共同的盲點：**它們全部無法偵測爬蟲的靜默失敗。**

假設哪天 Yourator 改版，`payload.jobs` 回傳空陣列。API 回 200 OK，`YouratorListScraper.scan()` 正常跑完，`ScanService` 呼叫 `finishRunSuccess(runId, ..., 0)`，pod 沒有重啟、沒有拋例外、沒有 error log、CPU 記憶體正常、Kafka 沒有 lag、DLQ 是空的。

**每一個基礎設施層與平台層的指標都會顯示「一切健康」，但這個系統已經停止產出價值。**

能偵測到這件事的只有一種指標：「某來源在過去 N 小時發現的職缺數為 0」。這是業務層指標，沒有任何現成 exporter 能提供。

`add-platform-observability` 的 Path A 已經從 `scrape_runs` 取得掃描成功率與發現筆數，可以覆蓋上述情境。但 Path A 有結構性限制：它只能回答「DB 裡現在的狀態是什麼」。以下這些「事件發生當下才知道」的資訊，DB 完全沒有記錄，**只能靠應用內埋點（Path B）**：

- **端到端 pipeline 延遲**——`JobEventEnvelope` 已帶有 `scrapedAt`，在 `DiscordNotifier` 推播成功的當下即可算出 `now - scrapedAt`。這正是本系統對使用者的核心承諾（新職缺多快送達），也是 SLO-1 的量測基礎。資料就在手上，只差沒有記錄下來。
- **Discord 推播成功／失敗次數**——`DiscordNotifier` 目前完全沒有計數。唯一能察覺失敗的途徑是等訊息重試三次後進入 `jobs.events.dlq`，中間的失敗完全不可見。
- **collector 端被平台限速（429）的次數**——`YouratorListScraper.fetchPage()` 有手刻的三次重試迴圈，但重試事實只存在於 warning log 的文字裡，無法聚合、無法告警。
- **normalizer parse 失敗次數**——parser 遇到格式異常時設計為回傳 null 並優雅降級（見 `add-job-posted-date/design.md`），這個「安靜地降級」的行為目前完全不可觀測。

另外還有一個埋點層面的既有缺口：`DiscordNotifier` 建構子以 `RestClient.builder()` 靜態工廠自行建立 client，而 `YouratorListScraper` 是注入 `RestClient.Builder`。前者不會被 Spring Boot 的 observability 自動組態納入，因此**同樣是對外 HTTP 呼叫，Yourator 有 client 端指標、Discord 沒有**——而 Discord 恰好是使用者可感知的最後一哩路。

最後，目前叢集內只有 kube-prometheus-stack 內建的告警規則，job-radar 專屬的 PrometheusRule 一條都沒有；Grafana 也沒有任何進入版控的 dashboard，違反「git 是唯一真相」（`homelab-infra/ARCHITECTURE.md`「GitOps 原則」）。

## What Changes

- 在 `collector` 與 `worker` 埋入 Micrometer 業務指標（Path B），涵蓋掃描結果、發現筆數、
  事件發布、推播成敗、解析失敗、限速重試
- 以 `JobEventEnvelope.scrapedAt` 為基準，在 notifier 端量測端到端 pipeline 延遲
- 修正 `DiscordNotifier` 改為注入 `RestClient.Builder`，使其對外呼叫納入自動 instrument
- 定義兩條 SLO 與其 error budget
- 建立 job-radar 專屬 PrometheusRule（進 `k8s` repo，由 ArgoCD 同步）
- 為告警規則建立 `promtool test rules` 單元測試
- 設定 Alertmanager 依 severity 路由至 Discord，告警 annotation 帶 runbook 連結
- 將 Grafana dashboard 收進版控（ConfigMap + sidecar 機制），由 ArgoCD 管理

## Non-goals

- **不做 distributed tracing**。Tempo 與 OTLP 埋點見 `add-distributed-tracing`。但本 change
  的 log 與埋點設計需預留與 trace 整合的空間（見 design.md）。
- **不重做 Path A**。`add-platform-observability` 建立的 `scrape_runs` 自訂查詢繼續保留，
  作為 Path B 的交叉驗證基準，兩者刻意並存。
- **不追求指標的完整覆蓋**。只埋「會被 dashboard 或告警實際使用」的指標。沒有消費者的
  指標一律不埋——它們只會增加 cardinality 與維護成本。
- **不引入 SLO 專用工具**（Sloth、Pyrra 等）。SLO 以手寫 PrometheusRule 表達，目的是理解
  burn rate 的計算方式而不是隱藏它。
- **不實作職缺消失偵測（closed sweep）**。屬於 Roadmap 004，與本 change 無關。
- **不改變既有的錯誤處理語意**。特別是 `DiscordNotifier` 加入計數後，例外仍必須向上拋出，
  以維持 `DefaultErrorHandler` → 三次重試 → DLQ 的既有行為（見 design.md）。

## Capabilities

### New Capabilities

- `business-metrics`：以應用內埋點量測系統是否真的在產出價值（掃描、發現、發布、推播、
  端到端延遲），涵蓋資料庫狀態無法回答的事件型指標
- `alerting`：SLO 定義、error budget、告警規則與其單元測試、Alertmanager 路由，
  以及靜默失敗偵測

### Modified Capabilities

- `discord-notification`：新增推播成敗的可觀測性，並修正 HTTP client 未被 instrument 的缺口；
  錯誤處理語意不變

## Impact

- **`job-radar` repo（會觸發 GitLab CI，全 change 僅推送一次）**：
  - `collector`：`ScanService` 埋點；`YouratorListScraper`／`CakeResumeListScraper` 的限速重試計數
  - `worker`：`NormalizerListener`／parser 的解析結果計數；`DiscordNotifier` 埋點 + 改為注入
    `RestClient.Builder`
  - `build.gradle.kts`：`collector` 與 `worker` 已有 `micrometer-registry-prometheus`，
    預期無新增依賴；`api` 需確認
  - 測試：以 `SimpleMeterRegistry` 驗證埋點的單元測試
- **`k8s` repo（不經 CI，ArgoCD 同步）**：新增 `platform/prometheus-rules/` 下的
  PrometheusRule；新增 Grafana dashboard 的 ConfigMap；Alertmanager 路由設定
- **`homelab-infra` repo**：Alertmanager 的 Helm values（severity 路由至既有 Discord webhook）；
  `ARCHITECTURE.md` 補上告警與 SLO 的說明
- **DB**：無 schema 變更
- **資源**：新增的 time series 數量有限（所有 label 值域皆有界），Grafana dashboard 不佔常駐資源
