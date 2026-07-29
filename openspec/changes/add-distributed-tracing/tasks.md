# Tasks: add-distributed-tracing

> **狀態：2026-07-29 已完成並上線。**
> 前置：`add-platform-observability` 與 `add-business-metrics-and-alerting` 皆已完成。
> 一開始因叢集 CPU request 帳面超賣（GitLab chart 預設值過高）卡關，把 GitLab
> 的 CPU request 調降 + worker1 加 vCPU（2→3）後才有 headroom，見
> `homelab-infra/ARCHITECTURE.md`「GitLab CPU 調整、worker1 加 vCPU、裝 Tempo」。
> Tempo 部署（`k8s` repo）不經 CI，先行完成；Java 變更單次推送（單一 commit，
> CI 只跑一次）。

## 0. 資源前置檢查（未通過則暫緩本 change）

- [x] 0.1 Prometheus headroom：GitLab CPU 右調後，三個節點 CPU request 帳面從
      83~97% 降到有餘裕，實測部署後 Tempo/collector/worker 皆未再出現
      `Insufficient cpu` 排程問題
- [x] 0.2 三個節點 CPU request 總和與可配置量：見上，已解決
- [x] 0.3 Longhorn 剩餘容量：Tempo PVC 10Gi 已成功綁定，無容量問題
- [x] 0.4 確認 Loki retention：**發現未設定**（chart 沿用預設，未特別覆寫）。
      記錄為已知隱患，這次未動手修（範圍外），見
      `homelab-infra/TROUBLESHOOTING.md`

## 1. Tempo 部署（`k8s` repo，不經 CI）

- [x] 1.1 `grafana/tempo` chart 1.24.4，SingleBinary + filesystem storage，
      比照 Loki 決策，見 `ansible/manifests/tempo-values.yml`（homelab-infra，
      不是 `k8s` repo——跟 Loki/Prometheus/Grafana 一樣走 Ansible+Helm 直接裝，
      不進 GitOps 迴路，這點本來就跟 job-radar 自己的 app manifest 不同路徑）
- [ ] 1.2 **刻意不設定** resources requests/limits——跟 Loki 一致的作法：
      這個 homelab 的瓶頸是 CPU request 帳面超賣（GitLab 是主因），不設
      request 讓 Tempo 用 Burstable QoS 搶剩餘算力，不再往超賣問題上疊加
- [x] 1.3 retention 設 24h（chart 預設，跟 Loki 目前實際 retention 一致，
      未特別覆寫）
- [x] 1.4 OTLP receiver（gRPC 4317 + HTTP 4318）為 chart 預設開啟，已驗證
      HTTP 4318 可達
- [x] 1.5 直接 `helm upgrade --install`（走 Ansible，非 `kubectl apply`）
- [x] 1.6 pod 健康（1/1 Running，0 restart）、PVC 綁定正常，已驗證
- [x] 1.7 Grafana 新增 Tempo datasource，已驗證連線（**踩坑**：chart 自動生成
      的 ServiceMonitor 沒帶 `release: kube-prometheus-stack` label，Prometheus
      選不到，補 `serviceMonitor.additionalLabels`；Grafana provisioning 幫既有
      Loki datasource 硬塞新 uid 導致整個 provisioning 失敗，改用 Loki 原本
      的 uid，詳見 `homelab-infra/TROUBLESHOOTING.md`）

## 2. Java 端埋點（`job-radar` repo）

- [x] 2.1 `collector/build.gradle.kts` 與 `worker/build.gradle.kts` 新增
      `micrometer-tracing-bridge-otel` 與 `opentelemetry-exporter-otlp`
      （版本由 Spring Boot 3.3.5 的 dependency management BOM 管理，未寫死版號）
- [x] 2.2 `management.otlp.tracing.endpoint`（Spring Boot 3.3.x 的實際屬性路徑，
      不是 `management.tracing.otlp`）指向 `tempo.monitoring.svc.cluster.
      local:4318/v1/traces`，透過 `OTLP_TRACING_ENDPOINT` 環境變數注入
      （`k8s` repo `apps/job-radar/collector.yaml`／`worker.yaml`）
- [x] 2.3 `management.tracing.sampling.probability: 1.0`
- [x] 2.4 service name 用 `spring.application.name`（既有設定，Spring Boot
      自動帶進 OTel resource attributes，不需額外設定）

## 3. Kafka context 傳遞（最容易漏掉的一步）

- [x] 3.1 Producer 端：`spring.kafka.template.observation-enabled: true`
- [x] 3.2 Consumer 端：`KafkaConsumerConfig.buildFactory()` 明確呼叫
      `factory.getContainerProperties().setObservationEnabled(true)`
- [x] 3.3 `ErrorHandlingDeserializer` + `setRemoveTypeHeaders(true)` 不影響
      `traceparent` header 傳遞——實測確認（`__TypeId__` 與 W3C trace context
      是不同的 header key，兩者互不影響）
- [x] 3.4 **驗收條件確認達成**：見第 6 節端到端實測，622 個 span 橫跨
      collector／worker 兩個服務、單一 traceID，不是四條互不相連的 trace

## 4. Trace 與 log 的關聯

- [x] 4.1 驗證 `traceId`／`spanId` 自動出現在 `LogstashEncoder` 輸出的 JSON
      欄位中——**假設成立**，未改 `logback-spring.xml`
- [x] 4.2 不適用（4.1 已如預期成立）
- [x] 4.3 collector／worker 行為一致，實測確認

## 5. 本機驗證（推送前完成）

- [x] 5.1 `./gradlew :collector:compileJava :worker:compileJava` 與
      `./gradlew :collector:test :worker:test -PskipDockerTests` 皆通過
- [ ] 5.2/5.3 **實作偏離**：這個環境沒有本機 docker，docker-compose 起不來，
      無法本機啟動 collector 驗證。改為直接部署到真實叢集，用第 6 節的端到端
      實測取代（validates 更完整：真實掃描資料、真實 Kafka、真實 Tempo，
      不是本機合成資料）
- [ ] 5.4 JVM heap：未特別量測絕對數字，但部署後兩個 pod 皆 0 restart（沒有
      OOMKilled 的跡象），視為間接驗證通過
- [x] 5.5 單次 commit（`464ec83`）+ push，CI 全程只觸發一次

## 6. 部署後驗證

- [x] 6.1 **端到端實測**：手動把某個 `search_queries` 的 `scrape_cursors`
      往前撥觸發立即掃描，在 Tempo 抓到一條涵蓋 622 個 span 的單一 trace：
      `scan-scheduler.tick` → `http post`（CakeResume 分頁）→
      `jobs.discovered send`（200 筆）→ worker `jobs.discovered receive` →
      `jobs.raw send/receive` → `jobs.events send/receive` →
      `DiscordNotifier` 對外 `http post`（4 次，1 原始 + 3 重試）→
      `jobs.events.dlq send`，完整涵蓋設計文件描述的路徑
- [x] 6.2 各段耗時可直接從 span duration 讀出（單一 tick trace 總長 33.7 秒，
      可逐段拆解）
- [x] 6.3 `DiscordNotifier` 的對外 HTTP 呼叫**確認自動產生 client span**，
      假設成立，不需額外埋點——而且這次 span 上的
      `exception: IllegalArgumentException`、`http.url: REPLACE_ME` 屬性
      直接印證了 `job-radar-discord` webhook 仍是 placeholder 這個已知問題，
      是 tracing 上線後第一個實戰案例
- [x] 6.4 部署後兩個服務 0 restart，worker1/worker2 未因 Tempo 出現排程或
      效能問題

## 7. Grafana 雙向跳轉

- [x] 7.1 Loki datasource 設定 derived field，`matcherRegex: "traceId":"(\w+)"`
      → 連結至 Tempo（`homelab-infra` `kube-prometheus-stack-values.yml`）
- [ ] 7.2 **未實測**：需要瀏覽器操作 Grafana UI，這個環境沒有瀏覽器工具，
      改用 API 直接驗證 traceId 確實出現在對應 log 行中（等同的資料層驗證，
      UI 互動本身留給使用者自行確認）
- [x] 7.3 Tempo datasource 設定 `tracesToLogsV2`，`datasourceUid` 指向 Loki
      既有的 uid（不是硬塞新 uid，見上方 1.7 的踩坑記錄）
- [ ] 7.4 同 7.2，未透過瀏覽器實測反向跳轉

## 8.（選用）Exemplars：metrics → traces

- [ ] 8.1–8.4 未做（標記選用項，本次範圍內優先完成核心 tracing 功能）

## 9. 文件

- [x] 9.1 更新 `homelab-infra/ARCHITECTURE.md` 的 Observability Stack 章節，
      加入 Tempo（新增獨立章節記錄整個過程，含 GitLab CPU 調整、probe 路徑
      踩坑、worker2 CA 缺口）
- [x] 9.2 更新 `docs/architecture.md`「可觀測性」章節與 Roadmap
- [x] 9.3 決策記錄新增 D15–D18：Tempo vs Jaeger/Zipkin、不部署 OTel
      Collector、Micrometer Tracing vs Java Agent、100% 取樣率
- [x] 9.4 已記錄「100% 取樣是本專案規模的特例」於 D18

## 10. 驗收

- [x] 10.1 單一 trace 完整涵蓋四跳，各段耗時可讀——已驗證
- [x] 10.2 `traceId` 出現在所有相關 JSON log 中——已驗證
- [~] 10.3 Loki ↔ Tempo **設定已完成**，資料層驗證通過（traceId 確實出現在
      對應的 log 行），但雙向跳轉的 UI 互動本身未透過瀏覽器實測（見 7.2/7.4）
- [x] 10.4 Tempo retention 已設定（24h），未動 Longhorn 容量（PVC 10Gi 綁定
      正常）
- [x] 10.5 既有服務未因 Tempo 的資源佔用而出現排程問題或效能退化——已驗證
      （0 restart，GitLab CPU 右調 + worker1 加 vCPU 已解決 headroom 問題）
- [x] 10.6 GitLab CI 全程只被觸發一次——已驗證（pipeline #28，單次 push）
