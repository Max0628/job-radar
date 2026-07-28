## Context

現有 pipeline 的實際跳數與程序邊界（`worker` 的三個 consumer 位於**同一個 JVM
程序**內，只是各自獨立的 consumer group，見 D8）：

```
collector (JVM A)
   └─ ScanService.runScan() → kafkaTemplate.send(jobs.discovered)
                                        │
worker (JVM B)                          ▼
   ├─ DetailFetcherListener   ← jobs.discovered   [group: worker-fetcher]
   │     └─ send(jobs.raw)
   ├─ NormalizerListener      ← jobs.raw          [group: worker-normalizer]
   │     └─ upsert PG + send(jobs.events)
   └─ DiscordNotifier         ← jobs.events       [group: worker-notifier]
         └─ RestClient.post() → Discord
```

即使 fetcher／normalizer／notifier 同處一個 JVM，它們之間仍以 Kafka 解耦，
執行緒與時間點完全分離——因此仍需要 trace context 經由 Kafka header 傳遞，
不能依賴 thread-local 傳播。

既有可利用的基礎：

- 三個服務的 `logback-spring.xml` 使用 `net.logstash.logback.encoder.LogstashEncoder`，
  會自動把 MDC 內容輸出為 JSON 欄位——因此 tracing 注入的 `traceId` / `spanId`
  預期不需要修改 logback 設定即可出現在 log 中
- Loki + Promtail 已在運行，log 已是結構化 JSON
- Grafana 已接上 Prometheus 與 Loki 兩個 datasource
- `worker` 與 `collector` 已有 `micrometer-registry-prometheus`，Micrometer 生態已就位

## Goals / Non-Goals

**Goals:**
- 一筆職缺從 collector 發現到 Discord 推播，能在 Grafana 中呈現為單一條 trace，
  各段耗時可直接讀出
- `traceId` 出現在所有相關的 JSON log 中，Loki 與 Tempo 可雙向跳轉
- 在資源受限的叢集上以最小元件數達成（不部署 Collector）

**Non-Goals:**
- 不追求 span 的完整覆蓋（例如每一次 DB 查詢一個 span）；先取得跨服務的骨架
- 不做效能最佳化——本專案的量級不需要取樣或批次調校
- 不追蹤同步查詢路徑（`api`／`frontend`）

## Decisions

### 選擇 Tempo 作為 trace 後端

| 選項 | 評估 |
|---|---|
| **Tempo** ✅ | 與既有 Grafana／Loki 同生態，共用查詢介面；SingleBinary + filesystem 的部署形態與既有 Loki 決策完全一致（見 `homelab-infra/ARCHITECTURE.md`「為什麼是 Loki 不是 ELK」的同一套理由：規模小、無 HA 需求、選最簡單的設定）；trace ↔ log ↔ metric 的互跳在 Grafana 全家桶內最順 |
| Jaeger | 功能成熟，但需要另一套 UI 與獨立儲存後端，與既有 Grafana 的整合體驗明顯較差 |
| Zipkin | 最簡單但功能最少，學習與實用價值都低 |

Tempo 的部署形態刻意複製 Loki 的既有決策（SingleBinary、filesystem），
理由與當初選 Loki 時相同，且能沿用同一套維運心智模型。

### 選擇 Micrometer Tracing 而非 OTel Java Agent

| | Micrometer Tracing ✅ | OTel Java Agent |
|---|---|---|
| 改動 | 加依賴 + 設定 | 零 code 改動，只改 k8s manifest |
| CI | 需觸發一次 | 完全不需要 |
| 可理解性 | span 如何產生、context 如何傳遞都是顯式的 | 黑盒，出問題難以推理 |
| 資源 | 較低 | agent 額外記憶體 + 拖慢啟動 |
| 與既有 Micrometer 的整合 | 原生同源 | 需處理兩套 instrumentation 的重疊 |

選 Micrometer Tracing。決定性理由有二：其一，本專案的目的包含理解機制本身，
黑盒方案的學習價值低；其二，服務已重度使用 Micrometer（metrics 全部走它），
Micrometer Tracing 與其同源，不會出現兩套 instrumentation 各自產生語意重疊資料的問題。

代價是需要觸發一次 CI，可接受——本 change 的 Java 變更集中且單次推送。

### 不部署 OpenTelemetry Collector

應用程式以 OTLP 直送 Tempo。Collector 的價值在於後端解耦、多目的地 fan-out、
集中式取樣與屬性改寫，本專案皆不需要。它是常駐元件，在 CPU 常態吃緊的叢集上
（`Insufficient cpu` 為已知常態）應避免。

若未來需要（例如同時送往雲端 APM），插入 Collector 只需改應用端的 endpoint 位址，
埋點程式碼完全不動——這正是選擇 OTLP 這個 vendor-neutral 協定所換得的彈性。

### 取樣率 100%

本專案的 trace 產生量約與職缺發現量同級（每日數百筆），全量取樣的儲存與傳輸成本
可忽略。真實生產環境需要投入大量心力設計取樣策略（head-based / tail-based、
錯誤優先取樣等），本專案的規模讓這個複雜度可以完全跳過。

代價是 Tempo 的儲存會隨時間成長，以 retention 設定控制（見下）。

### Spring Kafka 的 observation 必須明確啟用

Spring Boot 3.x 中，Kafka 的 observation 支援**預設為關閉**，需要顯式開啟：

- Producer 端：`spring.kafka.template.observation-enabled`
- Consumer 端：listener container 的 observation 屬性

本專案的 consumer container 由 `KafkaConsumerConfig.buildFactory()` 以程式碼建立
`ConcurrentKafkaListenerContainerFactory`，因此必須在該方法中對 container properties
明確啟用，僅設定 `application.yml` 的屬性**不會**生效。

這是本 change 最容易漏掉的一步：漏了不會有任何錯誤，只會得到三條互不相連的
獨立 trace（每一跳各自為一條），而不是一條完整的四跳 trace——而且乍看之下
「trace 有出來」，很容易誤判為成功。

`KafkaConsumerConfig` 使用 `ErrorHandlingDeserializer` 包裹 `JsonDeserializer`，
並設定 `setRemoveTypeHeaders(true)`。該設定只移除 Spring 的型別資訊 header，
不影響 W3C trace context 的 `traceparent` header，兩者互不干擾。

### trace 與 log 的關聯不需要修改 logback

Micrometer Tracing 會將 `traceId` / `spanId` 放入 SLF4J 的 MDC，
而 `LogstashEncoder` 預設會把 MDC 內容輸出為 JSON 欄位。因此三個
`logback-spring.xml` 預期不需要任何改動。

這一點必須實際驗證而非假設——若欄位未如預期出現，才考慮調整 encoder 設定。

### Grafana 的三向關聯

1. **Loki → Tempo**：Loki datasource 設定 derived field，從 log 的 `traceId` 欄位
   萃取並連結至 Tempo，使「看到一行錯誤 log → 直接跳到該次完整 trace」成立
2. **Tempo → Loki**：Tempo datasource 設定 trace-to-logs，從 span 跳回對應時間範圍
   與服務的 log
3. **Metrics → Traces（exemplars）**：Prometheus 的 histogram 樣本可攜帶 exemplar
   指向具體 trace，使「延遲分佈圖上這個離群點 → 該次請求的 trace」成立

前兩項是本 change 的必要範圍。第三項（exemplars）需要 Prometheus 啟用
exemplar storage 並在 Micrometer 端提供 span context，列為選用的進階項目——
它是三根支柱真正打通的體現，但不影響核心價值。

### Tempo 的 retention 必須設定

Tempo 使用 Longhorn PVC。Longhorn 容量不足已實際發生過（`homelab-infra/TROUBLESHOOTING.md`
「Storage（Longhorn）」，當時以線上活體擴容 120GB→200GB 解決）。100% 取樣加上
不設 retention，必然重演同一個問題。

retention 期間依實測的每日 trace 資料量決定，初期宜保守。

同樣的問題也適用於既有的 Loki——它同樣是 filesystem storage，本 change 應順帶
確認 Loki 的 retention 是否已設定。

## Risks / Trade-offs

- **[Risk] Tempo 排不進 CPU 吃緊的叢集，或排擠既有服務。** 這是本 change 最主要的風險。
  → 動工前先依 `add-platform-observability` tasks 9.4 記錄的 headroom 判斷；
  Tempo 設定明確的 requests/limits；必要時延後本 change。
- **[Risk] JVM 因 tracing 增加記憶體開銷而觸及 `-Xmx` 512MB 上限。**
  → 部署後觀察 heap 使用率（`add-platform-observability` 已使此指標可見），
  必要時調整 span 產生範圍。
- **[Risk] 忘記啟用 Kafka container 的 observation，得到四條斷裂的獨立 trace 卻誤判為成功。**
  → 驗收條件明確要求「單一 trace 涵蓋四跳」，而非「有 trace 產生」。
- **[Risk] Tempo 儲存無限成長撐爆 Longhorn。** → retention 為必要設定項，非選用。
- **[Trade-off] 100% 取樣在真實生產環境不可行，本專案的經驗無法直接外推。**
  → 接受並明確記錄；取樣策略等未來真的遇到規模問題時再學。
- **[Trade-off] 只追蹤非同步 pipeline，不追蹤查詢路徑。** → 查詢路徑是單跳同步呼叫，
  既有 metrics 已足夠；未來若 `api` 的查詢效能成為問題再擴充。

## 驗證策略

| 方法 | 驗證什麼 | 速度 |
|---|---|---|
| `./gradlew build` | 依賴解析與編譯 | 分鐘級 |
| 本機 `bootRun` + 觸發一次掃描 | span 確實產生、OTLP 送得出去 | 分鐘級 |
| 檢視本機 log 輸出 | `traceId` / `spanId` 確實出現在 JSON 欄位中 | 秒級 |
| `kubectl apply --dry-run=server` | Tempo manifest schema | 秒級 |
| Grafana Tempo 查詢 | 單一 trace 涵蓋完整四跳 | 分鐘級 |
| Loki 點擊 traceId 跳轉 | derived field 設定正確 | 分鐘級 |

Tempo 的部署（`k8s` repo）不經 CI，可先行部署並確認健康，再進行 Java 端變更——
如此 Java 推送時 OTLP 的目的地已經就緒，避免因後端未就緒而誤判埋點失敗。

## Migration Plan

1. 依 `add-platform-observability` tasks 9.4 的資料確認叢集 headroom；不足則本 change 暫緩
2. `k8s` repo 部署 Tempo，確認 pod 健康、PVC 正常、retention 已設定
3. Grafana 新增 Tempo datasource，確認可連線（此時尚無資料）
4. 本機完成 Java 端變更並驗證 span 產生與 `traceId` 進入 log
5. 單次 commit + push，CI 執行一次
6. 部署後在 Grafana 查詢一條完整 trace，確認涵蓋四跳
7. 設定 Loki ↔ Tempo 雙向跳轉並驗證
8. （選用）啟用 exemplars，打通 metrics → traces
9. 更新 `homelab-infra/ARCHITECTURE.md` 與 `docs/architecture.md`

回退：Java 變更 `git revert` 後重跑 CI；Tempo 移除即可，不影響任何既有服務——
tracing 為純附加，pipeline 的業務邏輯完全不依賴它。
