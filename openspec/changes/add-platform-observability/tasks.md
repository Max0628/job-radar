# Tasks: add-platform-observability

> **全程不改 `job-radar/src/` 與 `build.gradle.kts`，因此完全不觸發 GitLab CI。**
> 每個任務標注目標 repo。`k8s` repo 的變更由 ArgoCD 同步，`homelab-infra` 走 Ansible。

## 1. 修復既有 ServiceMonitor（`k8s` repo）

- [x] 1.1 部署前先記錄基準：port-forward Prometheus，記下 `/api/v1/targets` 的 active target 數量
      與 `prometheus_tsdb_head_series` 當前值，作為之後判斷 series 增量的依據
      （基準：28 個 target、73845 series）
- [x] 1.2 `apps/job-radar/collector.yaml` 的 Service 加 `metadata.labels: {app: collector}`
- [x] 1.3 `apps/job-radar/worker.yaml`、`apps/job-radar/api.yaml` 同樣處理
- [x] 1.4 **先只推 collector 一個**，等 ArgoCD sync 後確認 `/api/v1/targets` 出現
      `namespace="job-radar"` 且 `health="up"` 的 target；同時觀察 `prometheus_tsdb_head_series`
      的增量（單一 Spring Boot 服務數百條 series 是正常範圍）
- [x] 1.5 確認 collector 的 series 增量與 Prometheus 記憶體可接受後，再推 worker 與 api
- [x] 1.6 用 PromQL 確認以下「本來就存在、只是沒被採集」的指標真的有值：
      `jvm_memory_used_bytes`、`http_server_requests_seconds_count`、
      `hikaricp_connections_active`、resilience4j 的 retry 指標（worker，對應
      `YouratorDetailScraper` 的 `@Retry(name="yourator")`）——**`kafka_consumer_fetch_manager_records_lag`
      實測不存在**（見 1.7 附錄修正）
- [x] 1.7 把 1.6 實際查到的指標名稱與樣本值寫回本 change 的 `design.md` 附錄。**重要修正**：
      client 端 Kafka consumer metrics 完全不存在（`KafkaConsumerConfig` 手動 new
      `DefaultKafkaConsumerFactory` 繞過 Spring Boot 的 Micrometer 自動綁定），kafka-exporter
      的 broker 端 lag 從「建議」升級為「唯一來源」

## 2. Grafana 人工驗證（不進 git，僅本階段暫用）

- [x] 2.1 未透過 Grafana UI，直接 PromQL 查 heap 使用率：三服務都遠低於 512MB
      （collector 46.7MB／worker 46.4MB／api 25.8MB）
- [x] 2.2 CPU throttle 比例：`container_cpu_cfs_throttled_seconds_total` 全叢集不存在，
      改用 `container_cpu_cfs_throttled_periods_total` 算比例——collector 4.76%／
      api 0.63%／worker 0.45%，collector 較高但不嚴重，值得之後跟 `jobradar_scan_duration_seconds`
      交叉比對
- [x] 2.3 沒有留下任何 Grafana dashboard 狀態（直接查 API，比原計畫的匯入 dashboard 更乾淨）

## 3. kafka-exporter（`k8s` repo）

- [x] 3.1 新增 `apps/job-radar/kafka-exporter.yaml`：Deployment + Service + ServiceMonitor
- [x] 3.2 resources 設為 `requests: {cpu: 10m, memory: 32Mi}`
- [x] 3.3 dry-run 驗證 + 實際 apply 驗證，成功連上 Kafka broker
- [x] 3.4 三個 consumer group（`worker-fetcher`／`worker-normalizer`／`worker-notifier`）
      的 lag 指標可見
- [x] 3.5 topic 清單確認：`jobs.discovered.dlq`／`jobs.events.dlq` 存在且 offset 可見；
      **`jobs.raw.dlq` 從未被建立過**（`jobs.raw` 正規化流程從未觸發持續性失敗）——
      DLQ 深度告警需處理「topic 不存在」的情況
- [x] 3.6 交叉驗證：手動送測試訊息 + `worker` scale 到 0，broker 端 lag 正確反映為 1
      （即使沒有任何 consumer 存在）；恢復 worker 後訊息被 `NormalizerListener` 既有的
      優雅降級邏輯乾淨處理（未知 source，只記 WARN，未寫 DB、未發 Discord、未產生 DLQ）
- [x] 3.7 驗證完 `kubectl delete`，寫進 git 由 ArgoCD 建立（commit `36665c1`）

## 4. postgres_exporter：標準指標（`k8s` repo）

- [x] 4.1 建立唯讀 DB 角色 `postgres_exporter`（`pg_monitor` + 明確 `SELECT` 於
      `scrape_runs`／`jobs`／`search_queries`／`scrape_cursors`），驗證只能讀不能寫
- [x] 4.2 密碼以 SealedSecret 管理（`postgres-exporter-sealed-secret.yaml`）
- [x] 4.3 新增 `apps/job-radar/postgres-exporter.yaml`：Deployment + Service + ServiceMonitor
- [x] 4.4 連線數等標準指標可見（`pg_stat_database_numbackends` 等）
- [x] 4.5 連線數指標已可監控

## 5. postgres_exporter：Path A 業務指標（`k8s` repo）

- [x] 5.1 自訂查詢：`jobradar_scrape_runs_24h_total`／`_success`／`_jobs_discovered`、
      `jobradar_last_successful_scan_last_success_timestamp`
- [x] 5.2 查詢皆帶 24h 時間窗、`GROUP BY source`，未使用 `query_keyword` 當 label
- [x] 5.3 `EXPLAIN ANALYZE` 驗證：**目前選擇 Seq Scan，不是 index scan**——`scrape_runs`
      僅 98 筆資料，PostgreSQL optimizer 正確判斷全表掃描更快，這不是問題，是預期行為。
      待資料量成長後應重新驗證是否轉為 index scan（見 design.md 附錄）
- [x] 5.4 查詢以獨立 ConfigMap（`postgres-exporter-queries`）掛載
- [x] 5.5 交叉驗證：與 DB 直接查詢結果完全一致（8/8 兩個來源）
- [x] 5.6 確認 SLO-2 資料基礎已具備，不需等 Path B（commit `059f901`）

## 6. 叢集既有服務的 ServiceMonitor（`k8s` repo + `homelab-infra`）

- [x] 6.1 Longhorn：ServiceMonitor 建立，`longhorn_volume_actual_size_bytes` 等指標可見
- [x] 6.2 ingress-nginx：**原本連 metrics port 都沒開**（不只是沒建 ServiceMonitor），
      在 `homelab-infra` 的 `install-platform.yml` 加 `--set controller.metrics.enabled=true`
      並改用 `helm upgrade --install`（原本 `helm install` 已安裝就跳過，改值不會套用）
- [x] 6.3 確認 `nginx_ingress_controller_requests` 可見，即 frontend 真實使用者流量
- [x] 6.4 ArgoCD：**三個元件的 metrics port 都存在但沒有對應 Service**，在
      `argocd-values.yml` 開啟 `controller/server/repoServer.metrics.enabled`，同樣改用
      `helm upgrade --install`；`argocd_app_info` 確認 `k8s-gitops` 顯示 `Synced`/`Healthy`
- [x] 6.5 cert-manager：ServiceMonitor 建立，7 張憑證的到期天數皆可查詢（64–3624 天）

**意外發現**：兩次 `helm upgrade` 都沒 pin chart 版本，`helm repo update` 把 ArgoCD chart
從 10.1.0 意外升到 10.2.1（無問題，但記錄為已知技術債，見 design.md 附錄）。

## 7. 實體 host node-exporter（`homelab-infra` repo）

- [x] 7.1 新增 `ansible/playbooks/install-node-exporter.yml`，target `hypervisor` 群組
- [x] 7.2 綁定 `192.168.100.1`（virbr1），不綁 `0.0.0.0`
      （`files/node-exporter/prometheus-node-exporter.default`）
- [x] 7.3 `ansible-playbook --syntax-check` 通過（`--check` 需要 sudo，見下方阻塞說明）
- [x] 7.4 Prometheus 端 `additionalScrapeConfigs` 已加入並套用（`update-prometheus-scrape-config.yml`，
      `helm upgrade --reuse-values`，chart 版本 pin 在 87.6.0），實測 `t480-host` job/target
      已出現在 Prometheus
- [ ] 7.5 **阻塞：需要互動式 sudo 密碼，這個 session 沒有** ——`install-node-exporter.yml`
      需要你自己手動跑：
      ```
      cd ~/projects/homelab-infra/ansible
      ansible-playbook -i inventory/hosts.yml playbooks/install-node-exporter.yml --ask-become-pass
      ```
      跑完後 target 才會從 `unknown` 變 `up`，才能驗證 `node_cpu_scaling_frequency_hertz`
- [ ] 7.6 同上，待 7.5 完成後才能確認 `node_hwmon_temp_celsius`
- [ ] 7.7 同上，待 7.5／7.6 完成後才能把實測數字寫回 TROUBLESHOOTING.md

## 8. 文件

- [x] 8.1 更新 `homelab-infra/ARCHITECTURE.md`「Observability Stack」
- [x] 8.2 更新 `docs/architecture.md` Roadmap Phase 005 狀態（**未 push**，見下方 CI 說明）
- [x] 8.3 免費指標清單已整理進 `design.md` 附錄

## 9. 驗收

- [x] 9.1 `add-walking-skeleton/specs/deployment/spec.md` 的驗收條件**首次真正成立**
- [x] 9.2 job-radar 三個服務 + kafka-exporter + postgres-exporter，共 5 個 target 全部
      `health="up"`、`lastError` 為空
- [x] 9.3 所有手動驗證用資源已清除，ArgoCD `Synced`/`Healthy`，無 `OutOfSync`
- [x] 9.4 Prometheus 增量：target 28→41（+13），head series 73845→93244（**+26%**）——
      作為 `add-distributed-tracing` headroom 評估依據

## 額外發現：job-radar 的 CI 沒有真正「安全」的 push

`.gitlab-ci.yml` 的 `test:` stage 沒有 `rules: changes:` 守衛（只有 `build:frontend`
與 `package:*` 有），代表任何 push 到 `job-radar`——包含純文件變更——都會觸發完整的
Gradle test suite。這打破了本 change 原先「三個 change 用不同代價分級」的假設：
`k8s`／`homelab-infra` 真的完全不會觸發 CI，但 `job-radar` repo 本身沒有這種安全區。

因此 8.2 的文件變更**先在本地 commit，不 push**，等到 `add-business-metrics-and-alerting`
真的需要動 Java code、必須觸發一次 CI 時，一併推送，不多花一次 CI 時間在純文件變更上。
