# Spec: platform-observability

## ADDED Requirements

### Requirement: 應用服務 metrics 實際被採集
`collector`／`worker`／`api` 三個服務的 `/actuator/prometheus` SHALL 被 kube-prometheus-stack
實際抓取並儲存。Service 的 `metadata.labels` MUST 與對應 ServiceMonitor 的
`spec.selector.matchLabels` 相符——ServiceMonitor 比對的是 Service 資源自身的 label，
不是 Service `spec.selector` 指向的 Pod label。

#### Scenario: target 健康
- **WHEN** 查詢 Prometheus 的 `/api/v1/targets`
- **THEN** 存在 `namespace="job-radar"` 的 active target，且每個 target `health="up"`、
  `lastError` 為空

#### Scenario: 既有但未被儲存的指標變為可查詢
- **WHEN** 對 Prometheus 查詢 `jvm_memory_used_bytes`、`http_server_requests_seconds_count`、
  `hikaricp_connections_active`
- **THEN** 三者都回傳帶有 `namespace="job-radar"` 的樣本

#### Scenario: 無 metrics endpoint 的服務不被納入
- **WHEN** 檢視 job-radar namespace 的 Prometheus targets
- **THEN** `frontend`／`kafka`／`postgres` 三個 Service 不在其中（它們沒有
  `/actuator/prometheus`，納入只會產生永遠 `up=0` 的假 target）

### Requirement: Kafka 以 broker 視角提供 consumer lag
系統 SHALL 部署 `kafka-exporter`，從 broker 端計算 consumer group lag，
與 Spring Kafka client 自身回報的 lag 並存。告警用途 MUST 以 broker 端數據為準。

#### Scenario: 三個 consumer group 的 lag 可見
- **WHEN** 查詢 consumer group lag 指標
- **THEN** `worker-fetcher`／`worker-normalizer`／`worker-notifier` 三個 group 都有對應樣本

#### Scenario: consumer 不存在時 lag 仍可觀測
- **WHEN** `worker` Deployment 被 scale 到 0
- **THEN** Spring client 端的 lag time series 消失，但 kafka-exporter 回報的 lag 持續增長
  （這是 client 端指標無法覆蓋、必須由 broker 端提供的情境）

#### Scenario: DLQ 深度可觀測
- **WHEN** 查詢 topic offset 指標
- **THEN** `jobs.discovered.dlq`／`jobs.raw.dlq`／`jobs.events.dlq` 三個 topic 的 offset 可見

### Requirement: PostgreSQL 內部狀態可觀測
系統 SHALL 部署 `postgres_exporter`，使用專屬唯讀角色連線，憑證以 SealedSecret 管理（D10）。

#### Scenario: 連線數可觀測
- **WHEN** 查詢連線數指標
- **THEN** 目前連線數與上限可見，足以判斷是否逼近瓶頸

### Requirement: 爬取狀態指標在不修改應用程式碼下取得（Path A）
系統 SHALL 透過 `postgres_exporter` 的自訂查詢，把既有 `scrape_runs` 表聚合為 Prometheus
metrics，不需修改任何 Java 程式碼。

#### Scenario: 掃描成功率可計算
- **WHEN** 查詢 Path A 產出的指標
- **THEN** 可依 `source` 分別計算過去 24 小時的掃描總次數與成功次數，足以支撐
  「掃描成功率 ≥ 95%」的 SLO-2 判定

#### Scenario: 查詢不造成資料庫負擔
- **WHEN** 對每一條自訂查詢執行 `EXPLAIN ANALYZE`
- **THEN** 查詢走 `idx_scrape_runs_source_started_at` index scan，而非 seq scan

#### Scenario: label 基數受控
- **WHEN** 檢視 Path A 產出指標的 label
- **THEN** 只含 `source`（值域為有界的來源名稱），不含 `query_keyword`
  （由使用者從前端自由新增，值域無界，會造成 cardinality 爆炸）

#### Scenario: 與資料庫直接查詢結果一致
- **WHEN** 對 DB 直接執行同義 SQL，並與 Prometheus 中的對應指標比對
- **THEN** 兩者數值一致

### Requirement: 叢集平台服務納入採集
Longhorn、ingress-nginx、ArgoCD、cert-manager SHALL 各自建立 ServiceMonitor，
納入既有 kube-prometheus-stack。

#### Scenario: 儲存容量可觀測
- **WHEN** 查詢 Longhorn 指標
- **THEN** volume 的容量使用率可見（此問題已實際發生過，見 homelab-infra/TROUBLESHOOTING.md）

#### Scenario: 使用者流量可觀測
- **WHEN** 查詢 ingress-nginx 指標
- **THEN** frontend 對應 ingress 的請求數、延遲分佈、狀態碼可見。由於 D13 決定 frontend
  是唯一對外入口，此層數據即等同完整的使用者可見流量

#### Scenario: GitOps 狀態可觀測
- **WHEN** 查詢 ArgoCD 指標
- **THEN** application 的同步狀態與 OutOfSync 持續時間可見

#### Scenario: 憑證到期可觀測
- **WHEN** 查詢 cert-manager 指標
- **THEN** 各憑證的到期時間可見

### Requirement: 實體 host 納入採集
T480 host SHALL 執行 node-exporter，以 systemd 管理、由 Ansible 部署，
不部署於 k8s 叢集內（避免監控 hypervisor 的元件依賴該 hypervisor 承載的 VM）。

#### Scenario: TLP 效果可被量測
- **WHEN** 查詢 `node_cpu_scaling_frequency_hertz`
- **THEN** 實體 CPU 頻率低於 i5-8350U 的標稱 turbo 頻率，直接佐證
  `CPU_BOOST_ON_AC=0` 與 `CPU_SCALING_GOVERNOR_ON_AC=powersave` 已生效

#### Scenario: 不經由 Tailscale 對外暴露
- **WHEN** 檢視 node-exporter 的監聽位址
- **THEN** 綁定於 `192.168.100.1`（virbr1）而非 `0.0.0.0`
