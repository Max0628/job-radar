# Tasks: add-distributed-tracing

> 前置：`add-platform-observability` 與 `add-business-metrics-and-alerting` 皆已完成。
> **本 change 資源成本最高**，第 0 節的 headroom 檢查未通過則不應動工。
> Tempo 部署（`k8s` repo）不經 CI，先行完成；Java 變更單次推送。

## 0. 資源前置檢查（未通過則暫緩本 change）

- [ ] 0.1 依 `add-platform-observability` tasks 9.4 記錄的 Prometheus 記憶體與
      `prometheus_tsdb_head_series` 增量，評估叢集剩餘 headroom
- [ ] 0.2 檢視三個節點目前的 CPU request 總和與可配置量（`Insufficient cpu` 為已知常態）
- [ ] 0.3 檢視 Longhorn 剩餘容量，確認足以容納 Tempo 的 PVC
      （容量不足已實際發生過，見 `homelab-infra/TROUBLESHOOTING.md`）
- [ ] 0.4 確認既有 Loki 是否已設定 retention——同為 filesystem storage，
      若未設定則屬於同類隱患，順帶處理

## 1. Tempo 部署（`k8s` repo，不經 CI）

- [ ] 1.1 新增 Tempo manifest：SingleBinary 模式 + filesystem storage
      （比照既有 Loki 的部署決策，理由見 design.md）
- [ ] 1.2 設定明確的 resources requests/limits，避免排擠既有服務
- [ ] 1.3 **設定 retention**（非選用項）——100% 取樣且無 retention 必然撐爆 Longhorn
- [ ] 1.4 啟用 OTLP receiver（gRPC 或 HTTP），確認 Service 暴露對應 port
- [ ] 1.5 `kubectl apply --dry-run=server` 驗證後推送，由 ArgoCD 同步
- [ ] 1.6 確認 pod 健康、PVC 綁定正常
- [ ] 1.7 Grafana 新增 Tempo datasource，確認連線成功（此時尚無 trace 資料）

## 2. Java 端埋點（`job-radar` repo）

- [ ] 2.1 `collector/build.gradle.kts` 與 `worker/build.gradle.kts` 新增
      `micrometer-tracing-bridge-otel` 與 `opentelemetry-exporter-otlp`
- [ ] 2.2 `application.yml` 設定 OTLP endpoint 指向 Tempo 的 cluster 內部 Service DNS
- [ ] 2.3 設定取樣率為 100%（本專案量級允許，見 design.md）
- [ ] 2.4 設定 service name，使三個服務在 Tempo 中可區分

## 3. Kafka context 傳遞（最容易漏掉的一步）

- [ ] 3.1 Producer 端：啟用 `spring.kafka.template.observation-enabled`
- [ ] 3.2 Consumer 端：在 `KafkaConsumerConfig.buildFactory()` 中對
      `ConcurrentKafkaListenerContainerFactory` 的 container properties **明確啟用 observation**
      ——container 由程式碼建立，僅設定 `application.yml` 屬性不會生效
- [ ] 3.3 確認 `ErrorHandlingDeserializer` + `setRemoveTypeHeaders(true)` 不影響
      W3C `traceparent` header 的傳遞（預期不影響，需實測確認）
- [ ] 3.4 **驗收條件是「單一 trace 涵蓋完整四跳」，不是「有 trace 產生」**
      ——漏掉 observation 啟用時會得到四條互不相連的獨立 trace，乍看像成功

## 4. Trace 與 log 的關聯

- [ ] 4.1 本機驗證 `traceId` / `spanId` 是否自動出現在 `LogstashEncoder` 輸出的 JSON 欄位中
      （Micrometer Tracing 注入 MDC，encoder 預設輸出 MDC，預期不需改 `logback-spring.xml`）
- [ ] 4.2 若未如預期出現，才調整 encoder 設定；**不要預先修改**
- [ ] 4.3 確認三個服務的行為一致

## 5. 本機驗證（推送前完成）

- [ ] 5.1 `./gradlew build` 全數通過
- [ ] 5.2 本機啟動 `collector`，觸發一次掃描，確認 span 產生且 OTLP 送得出去
- [ ] 5.3 檢視本機 log 輸出，確認 `traceId` 欄位存在且同一次處理流程的多行 log 帶相同值
- [ ] 5.4 確認 JVM heap 使用量在加入 tracing 後仍安全低於 `-Xmx` 512MB 上限
- [ ] 5.5 全部通過後，**單次 commit + push**，CI 只跑一次

## 6. 部署後驗證

- [ ] 6.1 在 Grafana 的 Tempo 中查詢一條 trace，確認涵蓋
      `collector → jobs.discovered → fetcher → jobs.raw → normalizer → jobs.events → notifier → Discord`
      完整路徑
- [ ] 6.2 確認各段耗時可直接讀出，能回答「卡在哪一段」
- [ ] 6.3 確認 `DiscordNotifier` 的對外 HTTP 呼叫產生 client span
      （`add-business-metrics-and-alerting` 已將其改為注入 `RestClient.Builder`，
      因此應自動獲得，不需額外埋點——驗證此假設成立）
- [ ] 6.4 部署後觀察三個服務的 JVM heap 與節點 CPU，確認未因 tracing 惡化

## 7. Grafana 雙向跳轉

- [ ] 7.1 Loki datasource 設定 derived field，從 log 的 `traceId` 萃取並連結至 Tempo
- [ ] 7.2 實測：在 Loki 中找到一行 log，點擊 traceId 直接跳至對應 trace
- [ ] 7.3 Tempo datasource 設定 trace-to-logs，可從 span 跳回對應的 log
- [ ] 7.4 實測反向跳轉

## 8.（選用）Exemplars：metrics → traces

- [ ] 8.1 Prometheus 啟用 exemplar storage
- [ ] 8.2 Micrometer 端提供 span context，使 histogram 樣本攜帶 exemplar
- [ ] 8.3 實測：在 `jobradar_pipeline_latency_seconds` 的延遲分佈圖上，
      點擊離群樣本直接跳至該次的 trace
- [ ] 8.4 此項完成即代表 metrics／logs／traces 三根支柱真正互通

## 9. 文件

- [ ] 9.1 更新 `homelab-infra/ARCHITECTURE.md` 的 Observability Stack 章節，加入 Tempo，
      並更新該章節的架構圖
- [ ] 9.2 更新 `docs/architecture.md`「可觀測性」章節與 Roadmap
- [ ] 9.3 在 `docs/architecture.md` 的決策記錄中新增一則：
      為何選 Tempo 而非 Jaeger／Zipkin、為何不部署 OTel Collector、為何用
      Micrometer Tracing 而非 Java Agent（含被否決的選項，比照 D1–D14 的既有格式）
- [ ] 9.4 記錄「100% 取樣是本專案規模的特例，不可外推至生產環境」這個限制

## 10. 驗收

- [ ] 10.1 單一 trace 完整涵蓋四跳，各段耗時可讀
- [ ] 10.2 `traceId` 出現在所有相關 JSON log 中
- [ ] 10.3 Loki ↔ Tempo 雙向跳轉可用
- [ ] 10.4 Tempo retention 已設定，且經確認 Longhorn 容量不會被無限成長撐爆
- [ ] 10.5 既有服務未因 Tempo 的資源佔用而出現排程問題或效能退化
- [ ] 10.6 GitLab CI 全程只被觸發一次
