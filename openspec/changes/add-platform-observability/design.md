## Context

叢集已有 kube-prometheus-stack（chart 87.6.0）與 Loki／Promtail，見 `homelab-infra/ARCHITECTURE.md`「Observability Stack」。Prometheus CR 的實際設定為：

```
serviceMonitorSelector:          {"matchLabels": {"release": "kube-prometheus-stack"}}
serviceMonitorNamespaceSelector: {}          # 空 = 所有 namespace
```

也就是說 Prometheus 願意採納**任何 namespace** 中帶有 `release: kube-prometheus-stack` 這個 label 的 ServiceMonitor。`k8s` repo 的 `apps/job-radar/servicemonitors.yaml` 三個 ServiceMonitor 都帶了這個 label，因此**它們確實有被 Prometheus Operator 讀進來**，問題不在這一層。

問題在 ServiceMonitor 自己的 selector。三個 ServiceMonitor 都寫 `spec.selector.matchLabels: {app: <name>}`，而 `collector.yaml`／`worker.yaml`／`api.yaml` 裡的 Service 只有 `spec.selector`（挑 Pod 用），`metadata.labels` 完全沒有寫。Operator 找不到符合條件的 Service，於是產生了一份「沒有任何 target」的 scrape config，Prometheus 表現為完全沒有這個 job。

現有 Service 的樣子（以 collector 為例，`k8s/apps/job-radar/collector.yaml`）：

```yaml
kind: Service
metadata:
  name: collector
  namespace: job-radar        # ← 沒有 labels
spec:
  selector:
    app: collector            # ← 這是「挑哪些 Pod」，比對 Pod 的 label
```

`/api/v1/targets?state=any` 另外顯示 546 個 dropped targets，其中包含 job-radar 的 pod IP——那是 kube-prometheus-stack 內建的 annotation-based 全叢集探索路徑順手發現的，與 ServiceMonitor 是兩套獨立機制，不能拿來當成「有在採集」的證據。

## Goals / Non-Goals

**Goals:**
- 三個 Java 服務已經在產生但被丟棄的 metrics（JVM、HTTP server、HikariCP、Kafka client、resilience4j）真的被儲存
- 平台層（Kafka、PostgreSQL）與叢集既有服務（Longhorn、ingress-nginx、ArgoCD、cert-manager）納入採集
- 實體 T480 host 納入採集，讓 TLP 的效果可被量測
- 在不改 Java code 的前提下，先取得爬取成功率與發現筆數這兩個業務指標
- 建立一套「推 git 之前先驗證」的操作流程，因為本 change 完全不經過 CI

**Non-Goals:**
- 不定義任何告警與 SLO（`add-business-metrics-and-alerting` 負責）
- 不追求 metrics 的完整性，只求「該有出口的都有出口」；哪些指標真的值得畫、值得告警是下一個 change 的判斷
- 不解決 Kafka 單 broker 的可用性問題（D2 已決策接受）

## Decisions

**Service 補 `metadata.labels`，而不是改 ServiceMonitor 去比對別的東西**

兩種修法都可行：(a) 給 Service 加 `metadata.labels: {app: collector}`；(b) 改用 `spec.namespaceSelector` + 比對其他既有 label。選 (a) 因為它讓 `app: <name>` 這個 label 在 Pod、Service、ServiceMonitor 三個層級語意一致，是 k8s 生態的慣例寫法；(b) 會讓 selector 依賴一個不是為此目的存在的 label，之後容易再壞一次。

`frontend`／`kafka`／`postgres` 三個 Service 不加這個 label，因為它們沒有 `/actuator/prometheus`，加了只會產生一個永遠 `up=0` 的 target，製造假告警。Kafka／PostgreSQL 的 metrics 改由各自的 exporter 提供（見下）。

**Kafka 用 `kafka-exporter` 獨立 Deployment，不用 JMX exporter sidecar**

`kafka-exporter`（danielqsj）從 broker 的 Admin API 讀 consumer group offset 與 topic latest offset，兩者相減即為 lag。約 30MB RAM，零設定。JMX exporter 能給 broker 內部細節（request 佇列、under-replicated partitions、log flush 延遲），但需要掛 agent、寫一份冗長的 metric 改寫規則，且對單 broker 的 KRaft 部署，多數 broker 內部指標的診斷價值有限。先取 lag，JMX 留待真的需要診斷 broker 內部時再議。

**即使 Spring 端已有 consumer lag metric，仍然要裝 kafka-exporter**

`worker` 有 `micrometer-registry-prometheus` + `spring-kafka`，Spring Boot 會自動註冊 `kafka_consumer_fetch_manager_records_lag` 這類 client 端指標。但 client 端 lag 有一個致命性質：**consumer 進程死掉時，這個 metric 不是變大，而是整條 time series 消失**。任何寫成 `lag > N` 的告警規則，在 worker 掛掉時都不會觸發，因為根本沒有 series 可以比較。

`kafka-exporter` 從 broker 角度計算，consumer 死了 lag 照樣持續增長，是唯一能覆蓋「consumer 完全不在了」這個情境的來源。兩者並存不是重複：client 端提供細粒度的 fetch 行為，broker 端提供不受 consumer 存活影響的權威 lag。告警一律以 broker 端為準。

**PostgreSQL 用 `postgres_exporter`，並利用其自訂查詢功能實作業務指標（Path A）**

`postgres_exporter` 除了標準的 DB 內部指標，支援使用者提供 SQL、把結果集轉成 Prometheus metrics。`scrape_runs` 表已經由 `ScanService` 在每輪掃描時完整寫入（`startRun` / `finishRunSuccess` / `finishRunFailed`），欄位為：

```
scrape_runs(id, source, query_keyword, started_at, finished_at,
            pages_scanned, jobs_seen, jobs_discovered, jobs_deleted,
            status, error_message)
```

因此「每來源爬取成功率」與「每輪新缺數」這兩個業務指標，可以完全不改 Java code 就取得。這條路徑稱為 **Path A**。

**Path A 與 Path B（應用內埋點）不是同一件事做兩遍，而是覆蓋範圍不同**

| 想量測的東西 | Path A（查 DB） | Path B（Micrometer 埋點） |
|---|---|---|
| 各來源掃描成功率 | ✅ `status` 欄位 | ✅ counter with label |
| 各來源每輪發現筆數 | ✅ `jobs_discovered` | ✅ counter |
| DB 累積職缺總數 | ✅ `SELECT count(*)` | ❌ 記憶體 counter 無歷史，pod 重啟歸零 |
| 最後一次成功掃描距今多久 | ✅ `max(finished_at)` | ⚠️ pod 重啟後失真 |
| **端到端 pipeline 延遲** | ❌ DB 沒有這筆資料 | ✅ 只有這條路做得到 |
| HTTP 429 重試次數 | ❌ 只在 log 文字裡 | ✅ |
| Discord 推播成功／失敗 | ❌ 沒寫進 DB | ✅ |

判準：**Path A 回答「現在的狀態是什麼」（DB 是真相來源，重啟不影響）；Path B 記錄「剛剛發生了什麼事」（DB 沒寫的只能靠它）**。本 change 只做 Path A。

兩者重疊的前兩列刻意保留，作為交叉驗證：Path B 上線後，若 counter 推導值與 SQL 聚合值長期背離，代表其中一邊的邏輯有誤。

**Path A 的查詢必須限制時間窗，且只用既有索引**

`postgres_exporter` 每次被 Prometheus 抓取（30s）就會執行一次自訂 SQL。`scrape_runs` 是 append-only、會持續成長的表，全表掃描的聚合查詢會隨時間逐漸變成 DB 的負擔——這是「監控系統反過來拖累被監控系統」的典型形態。

因此所有自訂查詢一律：
- 加上 `started_at > now() - interval '24 hours'` 之類的時間窗條件
- 走既有的 `idx_scrape_runs_source_started_at (source, started_at DESC)`，不新增索引
- 只 `GROUP BY source`（值域為 `yourator`／`cakeresume`，有界且極小）

**明確不把 `query_keyword` 放進 label。** 它由 `search_queries` 設定表驅動，使用者可以從前端無限新增關鍵字，是一個無界的值域；作為 Prometheus label 會造成 time series 數量隨使用者操作而成長，是最典型的 cardinality 爆炸成因。需要按關鍵字分析時走 SQL 或前端，不走 metrics。

**exporter 連 DB 使用唯讀憑證，以 SealedSecret 管理**

沿用 D10，不新增明文 Secret。`postgres_exporter` 應使用專屬的唯讀角色，而非重用 `job_radar` 應用帳號——監控元件的權限外洩面應該最小化。若建立新角色需要 DDL，該操作屬於一次性維運（比照 `add-job-posted-date` 的回填 SQL 處理方式），不寫進 Flyway migration。

**host node-exporter 走 Ansible + systemd，不進 k8s**

監控 hypervisor 的元件跑在被 hypervisor 承載的 VM 裡是循環依賴——host 出問題（過熱降頻、磁碟滿）時，最需要數據的時刻正是 VM 可能一起受影響的時刻。因此 node-exporter 部署在 T480 host 本身，比照 `claude-sentinel` / `daily_log` 的 host-native 模式（`homelab-infra/ARCHITECTURE.md`「Host-Native Applications」）。

Prometheus 端以 `additionalScrapeConfigs` 的 static target 指向 host 在 `192.168.100.1`（virbr1）上的 endpoint。node-exporter 預設會輸出 `node_cpu_scaling_frequency_hertz`、`node_hwmon_temp_celsius` 等指標，正好對應 TLP 調整的 `CPU_BOOST_ON_AC` 與 `CPU_SCALING_GOVERNOR_ON_AC`。

**`frontend` 的使用者流量指標由 ingress-nginx 提供**

D13 已決定 frontend 是唯一對外入口、`api` 不開 Ingress。因此 ingress-nginx controller 的 metrics（每個 ingress 的請求數、延遲分佈、狀態碼）就等於真實使用者流量的完整樣貌，不需要在 nginx 容器內另外掛 exporter。

## Risks / Trade-offs

- **[Risk] 三個 Service 加上 label 後，Prometheus 一次新增大量 time series，記憶體上升。** JVM + Kafka client + HikariCP + resilience4j 的預設指標量不小（單一 Spring Boot 服務數百條 series 是常態），三個服務同時上線可能讓 Prometheus 記憶體明顯增加。→ 分批啟用（先 `collector`，觀察 Prometheus 記憶體與 `prometheus_tsdb_head_series` 後再開其餘兩個），必要時在 ServiceMonitor 加 `metricRelabelings` 丟棄不需要的指標。
- **[Risk] 自訂 SQL 查詢隨 `scrape_runs` 成長而變慢。** → 已用時間窗 + 既有索引限制；驗收時實際 `EXPLAIN ANALYZE` 確認走 index scan。
- **[Risk] 新增 exporter 排不進節點。** 叢集 CPU 常態吃緊，`Insufficient cpu` 是已知狀態（見 `docs/architecture.md` 資源預算）。→ exporter 一律設定極小的 requests（10m CPU / 32Mi），部署前先確認 headroom。
- **[Risk] 手動 `kubectl apply` 驗證時與 ArgoCD 的 `selfHeal: true` / `prune: true` 衝突。** → 驗證流程明定為「apply → 驗證 → `kubectl delete` → 寫進 git → 由 ArgoCD 建立」，不允許手動建立的資源長期存在於叢集，維持 git 是唯一真相（`homelab-infra/ARCHITECTURE.md`「GitOps 原則」）。
- **[Trade-off] Path A 的指標語意是 gauge（查詢當下的聚合結果），不是單調遞增的 counter，`rate()`／`increase()` 這類函數用不上，告警表達式會比 Path B 彆扭。** → 接受。Path A 的定位是「在不動 code 的前提下先有數據」，語意精確的版本由 Path B 在下一個 change 提供。

## 驗證策略（本 change 不經過 CI，因此驗證全部在推 git 之前完成）

`k8s` repo 的變更由 ArgoCD 直接讀取 git 同步，不走 GitLab CI；`homelab-infra` 走 Ansible。整個 change 沒有 pipeline 可以當安全網，驗證必須自己做。可用的回饋迴圈由快到慢：

| 方法 | 驗證什麼 | 速度 |
|---|---|---|
| `kubectl apply --dry-run=server` | manifest schema 正確、CRD 欄位沒拼錯 | 秒級 |
| `kubectl apply` 到叢集（暫時，不進 git） | ServiceMonitor 真的生效、exporter 真的抓得到資料 | 分鐘級 |
| Prometheus `/api/v1/targets` | target 出現且 `health: "up"`、`lastError` 為空 | 秒級 |
| Prometheus `/api/v1/query` 打 PromQL | 指標真的有值、label 如預期、cardinality 沒爆 | 秒級 |
| `EXPLAIN ANALYZE` 自訂查詢 | 走 index scan、執行時間可接受 | 秒級 |
| `ansible-playbook --check` | Ansible playbook 的 dry run | 秒級 |

驗證用的 `kubectl apply` 一律在確認後 `kubectl delete` 清除，再寫進 git 交由 ArgoCD 正式建立。

## Migration Plan

1. 修 Service labels（三個檔案，最小變更），推 `k8s` repo，確認 Prometheus 出現三個 up=1 的 target
2. 匯入社群現成的 JVM／Spring Boot dashboard 到 Grafana，人工確認資料合理（此階段允許 UI 操作，dashboard as code 是下個 change）
3. 部署 kafka-exporter，確認 consumer group lag 與三個 DLQ topic 的 offset 可見
4. 部署 postgres_exporter（標準指標），確認連線數等基本指標可見
5. 加上 Path A 自訂查詢，與 DB 直接下 SQL 的結果交叉比對
6. 補齊 Longhorn／ingress-nginx／ArgoCD／cert-manager 的 ServiceMonitor
7. Ansible 部署 host node-exporter，確認實體 CPU 頻率確實低於標稱值（驗證 TLP 生效）

每一步都是獨立可回退的：`k8s` repo 的部分 `git revert` + ArgoCD 自動同步即可，host 的部分停用 systemd unit 即可。

## 附錄：實測「免費指標」清單（tasks 1.6–1.7 產出，2026-07-28）

修好三個 Service 的 label 後，實際對 Prometheus 查詢確認的結果，**修正了本文件先前的一個假設**：

| 指標 | 預期 | 實測結果 |
|---|---|---|
| `jvm_memory_used_bytes`（heap/non-heap） | 免費 | 確認存在，三個服務都有 |
| `http_server_requests_seconds_count`（RED） | 免費 | 確認存在 |
| `hikaricp_connections_active` | 免費 | 確認存在 |
| `resilience4j_retry_calls_total{name="yourator"}` | 免費 | 確認存在，含 `kind` label 區分
  `failed_with_retry`／`failed_without_retry`／`successful_with_retry`／`successful_without_retry` |
| `kafka_producer_*`（producer 端） | 免費 | 確認存在（`ScanService` 用的 `KafkaTemplate` 是
  Spring Boot 自動組態的 bean） |
| `spring_kafka_template_seconds_*` / `spring_kafka_listener_seconds_*` | 免費 | 確認存在，
  per-container 的 success/failure 計數（見下方說明） |
| **`kafka_consumer_*`（含 consumer lag）** | **原文預期免費** | **完全不存在** |

**根因**：`worker/src/main/java/dev/jobradar/worker/config/KafkaConsumerConfig.java` 的
`buildFactory()` 以 `new DefaultKafkaConsumerFactory<>(...)` 手動建構 consumer factory，
繞過了 Spring Boot 自動組態的 `ConsumerFactory` bean。Spring Boot 的
`KafkaMetricsAutoConfiguration` 是透過 `DefaultKafkaConsumerFactoryCustomizer`
掛上 `MicrometerConsumerListener`，只對自動組態產生的 factory 生效；手動 `new` 出來的
factory 不會被這個 customizer 處理，因此完全沒有 client 端 consumer metrics。

`KafkaTemplate`（producer 端）之所以有 metrics，是因為 `ScanService` 注入的是 Spring Boot
自動組態的 bean，未被手動繞過。

**這修正了本文件先前的判斷**：原本認為「`kafka_consumer_fetch_manager_records_lag` 大概率免費
拿到，只是 client 端 lag 有 consumer 死亡時 series 消失的限制」——實測後這個指標根本不存在，
不是「存在但有限制」。也就是說 kafka-exporter（task 3）提供的 broker 端 lag，不是「兩個來源
其中比較可靠的一個」，而是唯一的來源。這讓 kafka-exporter 從「建議」升級為「必要」，
`add-business-metrics-and-alerting` 的告警設計不受影響（本來就已經決定只用 broker 端），
但這裡記錄下來避免未來誤以為還有 client 端數據可以參照比對。

**額外可用的資源**：`spring_kafka_listener_seconds_count`（label 為 `name`＝Spring 產生的
container bean id，如 `org.springframework.kafka.KafkaListenerEndpointContainer#0-0`，
`result`＝`success`／`failure`）提供了每個 listener container 的處理成功/失敗次數。
這雖然不是 consumer lag，但可以作為「該 consumer 是否持續在失敗」的補充訊號——
唯一的缺點是 container bean id 不含語意（無法直接看出對應哪個 topic／consumer group），
需要另外對照啟動順序或程式碼確認索引 0/1/2 分別對應 fetcher/normalizer/notifier。

**驗證方式記錄**（供之後同類工作參考）：`curl` Prometheus 的
`/api/v1/label/__name__/values`，用 `kafka` 關鍵字過濾，直接看有哪些指標名稱真的存在，
比對照文件猜測的名稱可靠。

**Prometheus target 數量變化**：三個 Service 的 label 修好後，active target 從基準值
（tasks 1.1 記錄的 28 個）增加到 31 個（+3）。`prometheus_tsdb_head_series` 的完整增量
待 task 9.4 統一記錄。

## 附錄：JVM heap 與 CPU throttling 實測（tasks 2.1–2.2 產出，2026-07-28）

未透過 Grafana UI 匯入 dashboard（該階段本就是拋棄式，不進 git），改直接對 Prometheus
下 PromQL 達成同樣的驗證目的，且不留下任何需要清除的 UI 狀態。

**Heap 使用率**：三個服務目前 heap used 都遠低於 `-Xmx 512MB`（collector 46.7MB、
worker 46.4MB、api 25.8MB）。目前沒有 OOM 風險，但這是低流量下的基準值，
`add-business-metrics-and-alerting` 埋點上線、流量增加後應重新檢視。

**CPU throttling**：原文預期查詢的 `container_cpu_cfs_throttled_seconds_total`
**在整個叢集中都不存在**（不限 job-radar），實際存在的是
`container_cpu_cfs_throttled_periods_total`（計次，不是計秒）。改用
`rate(container_cpu_cfs_throttled_periods_total[10m]) / rate(container_cpu_cfs_periods_total[10m])`
算出 throttle 比例：

| 服務 | throttle 比例 |
|---|---|
| collector | 4.76% |
| api | 0.63% |
| worker | 0.45% |

collector 的比例明顯高於另外兩個，合理推測與它的排程性質有關（`ScanScheduler` 週期性
觸發爬取，CPU 使用是陣發性尖峰而非平穩負載，容易在 2 vCPU 節點的 CFS quota 週期內
瞬間打滿）。目前比例不算嚴重，但值得在後續埋入 `jobradar_scan_duration_seconds`
（`add-business-metrics-and-alerting`）後交叉比對：若某次掃描耗時異常變長，
可以回頭檢查是否與 throttle 比例上升同時發生。

## 附錄：kafka-exporter 與 postgres_exporter 實測（tasks 3、4、5 產出，2026-07-28）

**kafka-exporter**：部署後三個 consumer group（`worker-fetcher`／`worker-normalizer`／
`worker-notifier`）與全部現存 topic 均可見。實測發現 `jobs.raw.dlq` topic **從未被建立過**
（`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`，topic 只在第一次真的有訊息寫入時才會出現）——
這代表 `jobs.raw` 的正規化流程至今從未觸發過需要 DLQ 的持續性失敗，是好消息，但也代表
`add-business-metrics-and-alerting` 的 DLQ 深度告警查詢，MUST 對「topic 不存在」與
「topic 存在但 offset 為 0」兩種情況都能正確處理（PromQL 對不存在的 series 直接回傳
無資料而非 0，天真的 `> 0` 判斷不會誤報，但也不能假設所有 DLQ topic 一開始就查得到）。

**broker 端 lag 驗證**（task 3.6）：由於實測當下已過 `ScanScheduler` 的活躍時段
（23:00 後），改為手動送一則測試訊息（`source="test"`）到 `jobs.discovered`，
並暫時把 `worker` scale 到 0。結果：`kafka_consumergroup_lag{consumergroup="worker-fetcher"}`
正確反映為 1（即使沒有任何 consumer 存在）。恢復 `worker` 後訊息被正常消費，
`NormalizerListener` 對未知 source 的既有優雅降級邏輯（`No payload parser registered
for source=test`）妥善處理了這筆測試訊息，僅記一行 WARN log，未寫入 `jobs` 表、
未觸發 Discord 通知、未產生 DLQ——測試乾淨，無需額外清理。

**postgres_exporter**：專屬唯讀角色 `postgres_exporter`（`pg_monitor` + 對
`scrape_runs`／`jobs`／`search_queries`／`scrape_cursors` 的明確 `SELECT`）驗證只能讀
不能寫（嘗試 `DELETE FROM jobs` 得到 `permission denied`，符合預期）。

**Path A 自訂查詢**：與直接對 DB 下同義 SQL 交叉比對，數值完全一致
（yourator／cakeresume 過去 24h 各 8 次掃描、8 次成功）。

**索引使用的實際情況（修正原計畫的假設）**：`EXPLAIN ANALYZE` 顯示查詢目前選擇
**Seq Scan**，不是走 `idx_scrape_runs_source_started_at`。這不是問題——`scrape_runs`
目前只有 98 筆資料，PostgreSQL 的 cost-based optimizer 正確判斷全表掃描比走索引更快
（避免索引查找的額外開銷）。原 tasks.md 5.3 寫的「驗證走 index scan」在目前資料量下
不成立，但這不代表設計錯誤——時間窗條件仍然正確且必要，只是索引要等資料量成長到
一定規模後才會被優化器選中。這件事本身是個值得記住的資料庫觀念：**EXPLAIN 顯示
Seq Scan 不等於「沒用到索引很糟」，要看資料量與優化器的成本估算**，之後若
`scrape_runs` 成長到數千筆以上，應該重新跑一次 `EXPLAIN ANALYZE` 確認優化器改用索引。

## 附錄：叢集既有服務 ServiceMonitor 實測（task 6 產出，2026-07-28）

**ArgoCD 與 ingress-nginx 原本完全沒有 metrics 出口**（不是「有出口沒接 ServiceMonitor」）：

- ArgoCD 三個元件（application-controller/server/repo-server）的 container 本身已在對應
  port（8082/8083/8084）吐 metrics，但沒有任何 Service 把這些 port 暴露出來——Helm chart
  的 `controller.metrics.enabled`／`server.metrics.enabled`／`repoServer.metrics.enabled`
  預設是 `false`。改了 `homelab-infra/ansible/manifests/argocd-values.yml` 開啟這三個開關，
  透過 `helm upgrade --install`（原本是 `helm install` 且已安裝就跳過，改成冪等的
  upgrade，之後改 values 才會真的套用）套用。
- ingress-nginx 的 container 連 metrics port 都沒有開（controller 預設不吐 metrics），
  在 `helm upgrade --install` 加上 `--set controller.metrics.enabled=true` 才產生。

兩次 `helm upgrade` 都驗證過沒有造成中斷：ArgoCD 升級後 `k8s-gitops` application
仍是 `Synced/Healthy`；ingress-nginx 升級後 LoadBalancer 的 external IP 維持
`192.168.100.200` 不變（helm upgrade 是 patch 不是重建 Service），且實測
`gitlab.192.168.100.200.nip.io` 仍正常回應。

**意外發現**：兩個 helm 指令都沒有 pin chart 版本（沿用原本 playbook 的寫法），
`helm repo update` 拉到的是當下最新版，導致 ArgoCD chart 從原本的 `10.1.0`
（app version v3.4.4）升級到 `10.2.1`（v3.4.5）。這次升級沒有造成問題，但這代表
這兩個 playbook**目前沒有版本鎖定**，之後任何時間重跑都可能拉到不同版本——如果要
避免非預期升級，應該在 `helm upgrade --install` 加上 `--version` 明確鎖定，
這件事本次先誠實記錄，不在本 change 範圍內處理（範圍是啟用 metrics，不是重新設計
這兩個 playbook 的版本管理策略）。

**實測到的真實資料**（確認每個 ServiceMonitor 都拿到有意義的值，不只是「target up」）：

| 元件 | 驗證的指標 | 實測結果 |
|---|---|---|
| cert-manager | `certmanager_certificate_expiration_timestamp_seconds` | 7 張憑證的到期天數：leaf 憑證 64–85 天、root CA 3624 天，符合預期。這是
  `add-business-metrics-and-alerting` 「憑證 30 天內到期」告警的資料基礎 |
| Longhorn | `longhorn_volume_actual_size_bytes` | 10 個 volume 的實際大小（bytes），對應之前 Longhorn 容量踩坑的場景 |
| ingress-nginx | `nginx_ingress_controller_requests` | 72 次請求（累計計數器），這是 frontend 的真實使用者流量（見 D13） |
| ArgoCD | `argocd_app_info{sync_status,health_status}` | `k8s-gitops` 顯示 `Synced`／`Healthy`，GitOps 狀態本身變得可觀測 |

驗證流程沿用既定模式：先 `kubectl apply` 到叢集手動驗證出真實數據，
確認後 `kubectl delete` 清除，再寫進 git 交由 ArgoCD 正式同步，避免手動建立的資源
與 `selfHeal: true` 衝突或造成 drift。

## 附錄：host node-exporter（task 7）——實作範圍受限於 sudo 權限

Prometheus 端已完整接好並驗證：`kube-prometheus-stack-values.yml` 加了
`additionalScrapeConfigs`，指向 `192.168.100.1:9100`；透過新增的
`update-prometheus-scrape-config.yml`（`helm upgrade --reuse-values`，chart 版本
明確 pin 在目前已安裝的 `87.6.0`）套用。實測 Prometheus 設定檔（`/api/v1/status/config`）
確認含有 `t480-host` 這個 job，`/api/v1/targets` 也確實出現這個 target——只是
health 是 `unknown`，因為 host 上實際的 node-exporter 還沒裝。

**這一步卡住了，原因是純技術限制，不是判斷問題**：這個 session 對 `t480`（`hypervisor`
群組，`ansible_connection: local`）沒有免密碼 sudo（`sudo -n -l` 直接回
「a password is required」，`/etc/sudoers.d/` 沒有對應的 NOPASSWD 規則），
而安裝 apt 套件、管理 systemd service 都需要 root。這個 session 沒有互動式終端機
可以輸入 sudo 密碼，我也不會、也不該向你索取這組密碼。

`install-node-exporter.yml`（含 `files/node-exporter/prometheus-node-exporter.default`，
把監聽位址鎖在 `192.168.100.1`、不綁 `0.0.0.0` 避免經 tailscale0 暴露）已經寫好、
syntax check 通過，**只差你自己手動跑一次**：

```bash
cd ~/projects/homelab-infra/ansible
ansible-playbook -i inventory/hosts.yml playbooks/install-node-exporter.yml --ask-become-pass
```

跑完後，Prometheus 的 `t480-host` target 應該會從 `unknown` 變成 `up`，
`node_cpu_scaling_frequency_hertz` 與 `node_hwmon_temp_celsius` 就能查到，
届時可以驗證 tasks.md 7.5／7.6 規劃的 TLP 效果佐證。

## 附錄：最終驗收（task 9 產出，2026-07-28）

- 三個 job-radar 服務 + kafka-exporter + postgres-exporter，共 5 個 target 全部
  `health="up"`、無 `lastError`
- ArgoCD `k8s-gitops` application：`Synced`／`Healthy`，無任何 `OutOfSync` 資源——
  所有這次的手動驗證用資源都已清除，最終狀態完全由 git 定義
- **Prometheus 資源總增量**：active target 從基準值 28 個增加到 41 個（+13）；
  `prometheus_tsdb_head_series` 從基準值 73845 增加到 93244（**+19399，約 +26%**）。
  這個數字是 `add-distributed-tracing` 評估 headroom 時的依據——Tempo 是三個 change
  裡資源成本最高的一項，動工前應該對照這次的實際增量，評估叢集還有多少餘裕。
- 待辦（不阻塞本 change 收尾，留給你自己執行）：`install-node-exporter.yml` 需要
  互動式 sudo，見上一則附錄的指令。
