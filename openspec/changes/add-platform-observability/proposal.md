## Why

`add-walking-skeleton/specs/deployment/spec.md` 有一條驗收條件寫著「三個 pod 出現在 Prometheus targets 且 `up=1`」。**這條從來沒有成立過。**

實測 Prometheus 的 `/api/v1/targets`：active targets 共 28 個，分佈在 `default`／`kube-system`／`monitoring` 三個 namespace，`job-radar` namespace **一個都沒有**。三個 ServiceMonitor（`collector`／`worker`／`api`）存在、路徑 `/actuator/prometheus` 正確、`release: kube-prometheus-stack` 這個給 Prometheus CR `serviceMonitorSelector` 用的 label 也帶對了，但 Prometheus Operator 從來沒有為它們產生過任何 scrape config。

根因是 `k8s` repo 裡三個 Service 的 `metadata.labels` 是空的（`kubectl get svc -n job-radar --show-labels` 全部 `<none>`），而 ServiceMonitor 的 `spec.selector.matchLabels` 比對的正是 **Service 自己的 `metadata.labels`**，不是 Service `spec.selector` 指向的 Pod label。兩者在 YAML 上都寫成 `app: collector`，長得一模一樣，但比對對象完全不同。

連帶的結果是：`worker` 已經引入的 `micrometer-registry-prometheus` + `spring-kafka` 自動註冊的 Kafka client metrics、`YouratorDetailScraper` 上 `@Retry(name = "yourator")` 由 resilience4j 自動註冊的重試 metrics、以及三個服務的 JVM／HikariCP／HTTP server metrics，**全部都在 pod 裡正常產生，但沒有任何一個被儲存下來**。

除此之外還有三個層面的盲區：

- **平台層（Kafka／PostgreSQL）完全沒有 metrics 出口**。Kafka 是裸的 `apache/kafka:3.8.0` StatefulSet，連 metrics port 都沒開；PostgreSQL 是裸的 `postgres:16-alpine`。broker 異常、DLQ topic 堆積、連線數逼近上限，目前都只能等下游服務報錯才間接察覺。
- **叢集內既有平台服務沒接上**。Longhorn、ingress-nginx、ArgoCD、cert-manager 都自帶 metrics endpoint，但都沒有 ServiceMonitor。其中 Longhorn 容量已經實際踩過坑（見 `homelab-infra/TROUBLESHOOTING.md`）。
- **實體 T480 本身是盲區**。node-exporter 由 kube-prometheus-stack 以 DaemonSet 部署在三個 VM 內，看到的是虛擬 CPU。剛完成的 TLP 降頻省電（關閉 turbo、powersave governor）所影響的實體 CPU 頻率、溫度、耗電，目前完全沒有任何數據可以佐證效果。

## What Changes

- 修正 `k8s` repo 中 `collector`／`worker`／`api` 三個 Service 的 `metadata.labels`，讓既有 ServiceMonitor 真的生效
- 為 Longhorn、ingress-nginx、ArgoCD、cert-manager 建立 ServiceMonitor（ingress-nginx 需先在 Helm values 開啟 metrics）
- 部署 `kafka-exporter`，取得 broker 視角的 consumer group lag 與 topic offset（含 DLQ topic 深度）
- 部署 `postgres_exporter`，取得連線數、交易速率、cache hit ratio、table 大小
- 透過 `postgres_exporter` 的自訂查詢，把既有 `scrape_runs` 表聚合成 Prometheus metrics（架構文件「可觀測性」章節列的「每來源爬取成功率、每輪新缺數」，在不改任何 Java code 的前提下先取得）
- 在 T480 host 本身以 Ansible 部署 node-exporter（`homelab-infra`），並讓 Prometheus 以 static config 抓取

## Non-goals

- **不改任何 Java code**。這是本 change 的核心約束：`k8s` repo 的變更由 ArgoCD 直接同步、不經過 GitLab CI，`homelab-infra` 走 Ansible。整個 change 完全不會觸發 pipeline，可以快速反覆迭代。需要動 `build.gradle.kts` 或 `src/` 的項目一律留給 `add-business-metrics-and-alerting`。
- **不寫任何告警規則**。PrometheusRule、Alertmanager 路由、SLO 定義全部留給 `add-business-metrics-and-alerting`。本 change 只負責「資料收得到」，不負責「資料異常時通知誰」。
- **不做 Grafana dashboard as code**。本階段允許直接匯入社群現成 dashboard 到 Grafana UI 做人工驗證；把 dashboard 收進 git 由 ArgoCD 管理是下一個 change 的範圍。
- **不做 distributed tracing**。Tempo 與 OTLP 埋點見 `add-distributed-tracing`。
- **不改 Kafka／PostgreSQL 的部署形態**。維持 D2 的單 broker KRaft StatefulSet、單一 PostgreSQL，只在旁邊加 exporter，不引入 Strimzi 或 operator。
- **不動 `frontend`**。它是 nginx 靜態服務，沒有 metrics endpoint；使用者可見的 RED 指標改由 ingress-nginx 的 metrics 提供（D13 決定 frontend 是唯一對外入口，因此 ingress 層的數據就等於真實使用者流量）。

## Capabilities

### New Capabilities

- `platform-observability`：叢集平台層（Kafka、PostgreSQL、Longhorn、ingress、ArgoCD、cert-manager）與實體 host 的 metrics 收集，以及在不改應用程式碼前提下由資料庫聚合而來的爬取狀態指標

### Modified Capabilities

- `deployment`：既有的「可觀測性最低限度」需求（三個服務 metrics 被 kube-prometheus-stack 抓取）補上**可執行的驗證方式**——原條文只描述期望狀態，沒有規定如何確認，導致它壞了將近一個月都沒被發現

## Impact

- **`k8s` repo**：`apps/job-radar/` 下 `collector.yaml`／`worker.yaml`／`api.yaml` 的 Service 加 labels；新增 `kafka-exporter.yaml`、`postgres-exporter.yaml`（含自訂查詢的 ConfigMap）；新增 `platform/servicemonitors.yaml`（Longhorn／ArgoCD／cert-manager／ingress-nginx）
- **`homelab-infra` repo**：新增 `ansible/playbooks/install-node-exporter.yml` 與對應的 systemd unit／設定檔，target 為既有的 `hypervisor` 群組（`t480`，`ansible_connection: local`）；Prometheus 的 additionalScrapeConfigs 需納入 host 的 endpoint
- **`job-radar` repo**：**僅有本 openspec 文件**，`src/` 與 `build.gradle.kts` 一律不動
- **DB**：無 schema 變更。`scrape_runs` 已有 `idx_scrape_runs_source_started_at (source, started_at DESC)` 索引，自訂查詢直接沿用，不需要新增索引
- **資源**：新增三個常駐元件（kafka-exporter、postgres_exporter 各約 30–50MB RAM；host node-exporter 在 VM 之外，不佔叢集資源）。叢集 CPU 常態吃緊（見 `docs/architecture.md` 資源預算），部署前需確認 headroom
