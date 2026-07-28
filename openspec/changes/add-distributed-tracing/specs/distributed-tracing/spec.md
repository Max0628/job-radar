# Spec: distributed-tracing

## ADDED Requirements

### Requirement: 端到端 trace 涵蓋完整 pipeline
系統 SHALL 為每一筆流經 pipeline 的職缺產生單一條 trace，涵蓋
`collector → jobs.discovered → fetcher → jobs.raw → normalizer → jobs.events → notifier → Discord`
的完整路徑。

#### Scenario: 單一 trace 涵蓋四跳
- **WHEN** 在 Grafana 的 Tempo 中查詢一筆已完成推播的職缺對應的 trace
- **THEN** 該 trace 為單一條，包含 collector 的掃描、三個 consumer 的處理、
  以及對 Discord 的 HTTP 呼叫，而非四條互不相連的獨立 trace

#### Scenario: 各段耗時可讀
- **WHEN** 檢視一條完整 trace
- **THEN** 每一段的耗時可直接讀出，足以回答「這筆職缺卡在哪一段」

### Requirement: Trace context 經 Kafka header 傳遞
Spring Kafka 的 observation SHALL 於 producer 與 consumer 兩端明確啟用。

#### Scenario: consumer container 的 observation 必須以程式碼啟用
- **WHEN** consumer container 由 `KafkaConsumerConfig.buildFactory()` 以程式碼建立
- **THEN** observation MUST 在該方法中對 container properties 明確啟用；
  僅設定 `application.yml` 的屬性不會生效

#### Scenario: 型別 header 移除不影響 trace 傳遞
- **WHEN** `JsonDeserializer` 設定 `setRemoveTypeHeaders(true)`
- **THEN** W3C `traceparent` header 仍正常傳遞，trace 不中斷

#### Scenario: 斷裂的 trace 不算通過
- **WHEN** 驗收 trace 功能
- **THEN** 判準為「單一 trace 涵蓋四跳」而非「有 trace 產生」——
  未啟用 observation 時仍會產生 trace，只是每一跳各自獨立，容易被誤判為成功

### Requirement: Trace 與 log 雙向關聯
`traceId` 與 `spanId` SHALL 出現在既有的結構化 JSON log 中，
且 Grafana 中 Loki 與 Tempo 可雙向跳轉。

#### Scenario: traceId 自動進入 log
- **WHEN** 服務輸出任何一行 log
- **THEN** JSON 中含有 `traceId` 欄位；同一次處理流程產生的多行 log 帶相同的值

#### Scenario: 不預先修改 logback 設定
- **WHEN** 導入 Micrometer Tracing
- **THEN** `logback-spring.xml` 預期不需修改（`LogstashEncoder` 預設輸出 MDC 內容）；
  僅在實測未出現該欄位時才調整

#### Scenario: 從 log 跳至 trace
- **WHEN** 在 Loki 中檢視一行帶 `traceId` 的 log
- **THEN** 可直接點擊跳轉至 Tempo 中的對應 trace

#### Scenario: 從 trace 跳至 log
- **WHEN** 在 Tempo 中檢視一個 span
- **THEN** 可跳轉至對應時間範圍與服務的 Loki log

### Requirement: 以 OTLP 直送 Tempo，不部署 Collector
應用程式 SHALL 以 OTLP 協定直接將 trace 送往 Tempo，不部署 OpenTelemetry Collector。

#### Scenario: 元件數最小化
- **WHEN** 檢視本次新增的常駐服務
- **THEN** 只有 Tempo 一個；OpenTelemetry 以函式庫形式存在於應用程式中，
  不是獨立部署的元件

#### Scenario: 後端可替換
- **WHEN** 未來需要更換 trace 後端
- **THEN** 僅需修改 OTLP endpoint 設定，埋點程式碼不需變動

### Requirement: Tempo 儲存必須設定 retention
Tempo SHALL 設定明確的資料保留期限。

#### Scenario: 儲存不得無限成長
- **WHEN** 檢視 Tempo 設定
- **THEN** retention 已明確設定——100% 取樣加上無 retention 必然撐爆 Longhorn，
  該問題已實際發生過（見 homelab-infra/TROUBLESHOOTING.md）

### Requirement: 資源使用不得損害既有服務
本功能 SHALL 在不造成既有服務排程失敗或效能退化的前提下運行。

#### Scenario: 部署前確認 headroom
- **WHEN** 準備部署 Tempo
- **THEN** 已依據 `add-platform-observability` 記錄的資源增量評估叢集剩餘容量；
  不足時本功能延後而非勉強部署

#### Scenario: JVM 記憶體維持在限制內
- **WHEN** 應用程式加入 tracing 後運行
- **THEN** heap 使用量仍安全低於 `-Xmx` 512MB 的硬性限制

### Requirement: 取樣率設定與其適用範圍
系統 SHALL 採用 100% 取樣，並明確記錄此為專案規模之特例。

#### Scenario: 全量取樣
- **WHEN** 任何一筆職缺流經 pipeline
- **THEN** 其 trace 完整保留，不做取樣捨棄

#### Scenario: 限制被明確記錄
- **WHEN** 檢視架構文件
- **THEN** 已記載「100% 取樣是本專案規模的特例，不可外推至生產環境」
