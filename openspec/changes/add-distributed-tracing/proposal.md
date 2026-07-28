## Why

前兩個 change 完成後，metrics 與 logs 都到位了，但有一類問題它們合起來仍然回答不了：
**「這一筆職缺為什麼三小時後才推播？卡在哪一段？」**

job-radar 是一條四跳的非同步 pipeline（D3、D8）：

```
collector → jobs.discovered → worker(fetcher) → jobs.raw
          → worker(normalizer) → jobs.events → worker(notifier) → Discord
```

`add-business-metrics-and-alerting` 的 `jobradar_pipeline_latency_seconds` 能告訴你
「端到端花了三小時」，consumer lag 能告訴你「某個 group 有堆積」，但要確定是
fetcher 對外抓取被限速、normalizer 寫 DB 卡住、還是 notifier 被 Discord 限流，
目前只能翻 Loki 的 log 並人工對時間戳。

而且對得非常辛苦，因為**現在的 log 沒有任何關聯識別碼**。三個服務的
`logback-spring.xml` 都以 `LogstashEncoder` 輸出結構化 JSON，欄位查詢沒問題，
但一筆職缺跨四個處理階段所產生的多行 log 之間，唯一的關聯線索是
`sourceJobId` 這個業務欄位——而 fetcher 階段的失敗 log 未必帶得到它。

Distributed tracing 正是為此存在：它把一次完整的處理流程串成一條 trace，
每一段是一個 span，直接畫出各段耗時。而且 trace context 一旦導入，
`traceId` / `spanId` 會自動進入 MDC、被既有的 `LogstashEncoder` 寫進 JSON log，
Loki 與 trace 之間就能互相跳轉——**這同時也把上述「log 缺關聯識別碼」的問題一併解決**。

需要澄清一個常見誤解：**OpenTelemetry 與 Tempo 不是二選一，它們不在同一層。**

| 角色 | 職責 | 本 change 的選擇 |
|---|---|---|
| 產生 trace | 在 JVM 內產生 span、跨服務傳遞 context | OpenTelemetry（經 Micrometer Tracing bridge） |
| 傳輸協定 | trace 資料的格式與傳輸 | OTLP |
| 儲存與查詢 | 存放 trace、提供查詢介面 | **Tempo** |

OpenTelemetry 對 traces 的地位，等同 Prometheus exposition format 對 metrics——
它是大家講好的語言；Tempo 的地位則等同 Prometheus TSDB——它是存放的地方。
兩者會同時使用。

因此本 change 實際「新裝的服務」**只有 Tempo 一個**；OpenTelemetry 對 job-radar
而言是幾行 `build.gradle.kts` 依賴，不是一個要部署的元件。

## What Changes

- 在 `k8s` repo 部署 Tempo（SingleBinary 模式 + filesystem storage，比照既有 Loki 的部署決策）
- `collector` 與 `worker` 加入 Micrometer Tracing + OTLP exporter 依賴與設定
- 明確啟用 Spring Kafka 的 observation（預設關閉），使 trace context 經 Kafka header
  跨越三跳 topic 傳遞
- Grafana 新增 Tempo datasource，並設定 Loki ↔ Tempo 雙向跳轉
- 確認 `traceId` / `spanId` 出現在既有的 JSON log 中

## Non-goals

- **不部署 OpenTelemetry Collector**。應用程式直接以 OTLP 送往 Tempo。Collector 的價值在於
  後端解耦、多目的地 fan-out、集中式取樣與過濾，本專案都用不到，而它是一個會消耗
  CPU／記憶體的常駐元件——在 CPU 常態吃緊的叢集上，少一個是一個。未來若需要，
  應用端不需改動。
- **不使用 OTel Java Agent**。見 design.md 的取捨說明。
- **不做取樣策略**。本專案的 trace 量級允許 100% 取樣（見 design.md）。
- **不追蹤 `api` 與 `frontend` 的請求路徑**。使用者查詢路徑是單跳同步呼叫，
  ingress-nginx 與 `http_server_requests_seconds` 已足以描述，導入 tracing 的邊際價值低。
  本 change 只涵蓋非同步 pipeline。
- **不建立 trace 相關的告警**。trace 的用途是事後診斷，不是即時偵測；
  偵測職責屬於 `add-business-metrics-and-alerting` 的 metrics 告警。
- **不改變任何業務邏輯或錯誤處理語意**。

## Capabilities

### New Capabilities

- `distributed-tracing`：跨 collector 與 worker 三個 consumer 的端到端請求追蹤，
  含 Kafka context 傳遞、trace 與 log 的雙向關聯，以及 trace 資料的儲存與查詢

## Impact

- **`k8s` repo（不經 CI）**：新增 Tempo 部署（Deployment/StatefulSet + Service +
  PVC）；Grafana datasource 設定（Tempo + Loki 的關聯欄位）
- **`job-radar` repo（觸發 CI，單次推送）**：
  - `collector`／`worker` 的 `build.gradle.kts` 新增
    `micrometer-tracing-bridge-otel` 與 `opentelemetry-exporter-otlp`
  - `application.yml` 新增 OTLP endpoint 與取樣設定
  - `KafkaConsumerConfig.buildFactory()` 需明確啟用 container 的 observation
    （Spring Boot 預設關閉）
  - `logback-spring.xml` **預期不需改動**（`LogstashEncoder` 會自動輸出 MDC 內容），
    驗證後確認
- **`homelab-infra` repo**：`ARCHITECTURE.md` 的 Observability Stack 章節補上 Tempo
- **資源（本 change 最主要的風險）**：Tempo 是三個 change 中資源成本最高的新增元件。
  動工前 MUST 先確認叢集 headroom——依據為 `add-platform-observability` tasks 9.4
  記錄的 Prometheus 記憶體與 series 增量。另外應用端 JVM 也會因 tracing 增加記憶體開銷，
  而 `-Xmx` 硬限制為 512MB（見 `openspec/config.yaml` 硬規則）。
- **儲存**：Tempo 使用 Longhorn PVC，需設定 retention。Longhorn 容量已實際踩過坑
  （見 `homelab-infra/TROUBLESHOOTING.md`），不設 retention 會重演。
