# Tasks: add-business-metrics-and-alerting

> 前置：`add-platform-observability` 必須先完成（Prometheus 真的採集得到 job-radar 的指標）。
> **Java 變更只推送一次**，所有驗證在本機完成。`k8s` repo 的部分不經 CI，可先行迭代。

## 0. 前置確認

- [ ] 0.1 核對 `add-platform-observability` tasks 1.7 產出的「免費指標清單」，確認本 change
      規劃要埋的指標沒有任何一個已經由 Actuator／spring-kafka／resilience4j 免費提供
- [ ] 0.2 確認 `worker` 與 `collector` 的 `build.gradle.kts` 已有
      `micrometer-registry-prometheus`（預期已有，無需新增依賴）

## 1. `k8s` repo 先行：不依賴 Java 變更的告警（不經 CI）

- [ ] 1.1 新增 `platform/prometheus-rules/job-radar-business.yaml`，以 Path A
      （`scrape_runs` 自訂查詢）的指標撰寫「某來源 6h 內發現 0 筆」的靜默失敗告警
- [ ] 1.2 該規則 MUST 帶活躍時段條件——`ScanScheduler.isWithinActiveHours()` 限制掃描僅在
      台灣時間 08:00–23:00，不加時段條件則每天凌晨必然誤報
- [ ] 1.3 以 Path A 指標撰寫 SLO-2（掃描成功率 ≥ 95%）的告警規則
- [ ] 1.4 新增 DLQ 深度 > 0 的告警（資料來源為 `add-platform-observability` 建立的
      kafka-exporter topic offset 指標）
- [ ] 1.5 `promtool check rules` 驗證語法
- [ ] 1.6 撰寫 `promtool test rules` 測試，至少涵蓋：
      - 活躍時段內連續 6h 為 0 → 應 firing
      - **凌晨時段連續 6h 為 0 → 不應 firing**（最容易寫錯的案例）
      - 掃描成功率 94% → 應 firing；96% → 不應 firing
      - DLQ offset 從 0 變 1 → 應 firing
- [ ] 1.7 `kubectl apply --dry-run=server` 驗證 schema 後推送，由 ArgoCD 同步

## 2. Alertmanager 路由（`homelab-infra` repo）

- [ ] 2.1 設定依 `severity` 分級路由至既有 Discord webhook（critical／warning／info
      可用不同頻道或不同 grouping 間隔）
- [ ] 2.2 所有告警的 annotation 加上 runbook 連結，指向 `homelab-infra/TROUBLESHOOTING.md`
      的對應章節（Longhorn、TLP 等章節已存在，可直接連）
- [ ] 2.3 設定合理的 `group_wait` / `group_interval` / `repeat_interval`，避免單一事件
      在 Discord 洗版
- [ ] 2.4 **實際觸發一次告警驗證端到端送達**：暫時把某條告警門檻改成必然觸發，
      確認 Discord 真的收到、內容可讀、runbook 連結可點，之後改回
      —— **未經實際觸發驗證的告警等於不存在**
- [ ] 2.5 確認 kube-prometheus-stack 內建的 `Watchdog`（dead man's switch）目前的路由狀態，
      明確處置：接上外部檢查，或在文件中記錄為刻意忽略並說明理由

## 3. `collector` 埋點（`job-radar` repo）

- [ ] 3.1 `ScanService.runScan()`：成功路徑記錄 `jobradar.scan{source, result="success"}`，
      失敗路徑（catch 區塊）記錄 `result="failure"`
- [ ] 3.2 `ScanService.runScan()`：記錄 `jobradar.jobs.discovered{source}`，
      值為 `result.discovered().size()`
- [ ] 3.3 `ScanService.runScan()`：以 Timer 記錄 `jobradar.scan.duration{source}`
- [ ] 3.4 `YouratorListScraper.fetchPage()`：429 重試時記錄
      `jobradar.scrape.retry{source="yourator", reason="rate_limited"}`
      （目前重試事實只存在於 warning log 文字中）
- [ ] 3.5 檢查 `CakeResumeListScraper` 是否有等價的重試邏輯，有則比照埋點
- [ ] 3.6 **確認所有 label 值域有界**：不得出現 `query_keyword`、`sourceJobId`、
      `url`、`e.getMessage()`（見 design.md「Label 基數規則」）

## 4. `worker` 埋點（`job-radar` repo）

- [ ] 4.1 `NormalizerListener`／parser：記錄 `jobradar.parse{source, result}`，
      涵蓋 parser 優雅降級（回傳 null）的情況——目前這個「安靜降級」完全不可觀測
- [ ] 4.2 `NormalizerListener`：記錄 `jobradar.events.published{source, type}`
- [ ] 4.3 `DiscordNotifier.onEvent()`：記錄 `jobradar.notification{result}`
- [ ] 4.4 `DiscordNotifier.onEvent()`：推播成功後記錄
      `jobradar.pipeline.latency` = `Duration.between(event.scrapedAt(), Instant.now())`
- [ ] 4.5 該 Timer MUST 設定 `serviceLevelObjectives` 於 5 分鐘處產生 bucket，
      讓 SLO-1 的 SLI 是精確計數而非 `histogram_quantile()` 估算值
- [ ] 4.6 `jobradar.pipeline.latency` **不加 `source` label**（SLO-1 描述 pipeline 整體）

## 5. `DiscordNotifier` 錯誤處理（`job-radar` repo）— 高風險項

- [ ] 5.1 加入計數用的 try/catch 後，catch 區塊 **MUST 重新拋出原例外**
- [ ] 5.2 撰寫測試：模擬 webhook 回傳失敗，斷言例外確實向上傳播
      （若被吞掉，訊息會被視為處理成功並 commit offset，職缺永久遺失、DLQ 永遠為空，
      同時破壞 D5 的 at-least-once 保證，且**表面上完全看不出異常**）
- [ ] 5.3 建構子改為注入 `RestClient.Builder`（比照 `YouratorListScraper`），
      使 Discord 呼叫納入 Spring Boot observability 自動組態
- [ ] 5.4 順帶檢查 `CakeResumeListScraper`、`YouratorDetailScraper` 的 `RestClient`
      取得方式，統一為注入 builder

## 6. 埋點測試（`job-radar` repo）

- [ ] 6.1 以 `SimpleMeterRegistry` 為每個新增 meter 撰寫單元測試：
      斷言在正確時機遞增、label 值正確、meter 名稱正確
- [ ] 6.2 特別涵蓋失敗路徑：掃描拋例外時 `result="failure"` 確實被記錄
- [ ] 6.3 `jobradar.pipeline.latency` 測試：餵入已知的 `scrapedAt`，斷言記錄的
      duration 落在預期 bucket

## 7. 本機驗證（推送前完成）

- [ ] 7.1 `./gradlew build` 全數通過
- [ ] 7.2 本機 `bootRun` 啟動 `collector`，`curl /actuator/prometheus`，
      確認新指標出現且名稱符合 design.md 的對照表（程式碼寫點號、匯出為底線）
- [ ] 7.3 同樣驗證 `worker`
- [ ] 7.4 檢查匯出內容中沒有非預期的高基數 label
- [ ] 7.5 全部通過後，**單次 commit + push**，CI 只跑一次

## 8. 部署後驗證與 Path A 交叉比對

- [ ] 8.1 確認新指標出現在 Prometheus
- [ ] 8.2 `jobradar_scan_total` 推導出的成功率 vs Path A 由 `scrape_runs` 聚合的成功率，
      兩者比對一致
- [ ] 8.3 `jobradar_jobs_discovered_total` vs Path A 的 `jobs_discovered` 聚合值比對一致
- [ ] 8.4 若不一致，必須查明原因，不可任選一方採信

## 9. 依賴 Path B 的告警（`k8s` repo，不經 CI）

- [ ] 9.1 SLO-1（pipeline 延遲 99% < 5min）的 SLI 規則與 error budget 計算
- [ ] 9.2 簡化版雙視窗 burn rate 告警（1h 快速燃燒 → warning，6h 緩慢燃燒 → info）
- [ ] 9.3 Discord 推播失敗率 > 10%（15m）告警
- [ ] 9.4 consumer lag 持續成長 15 分鐘告警（使用 kafka-exporter 的 broker 端數據，
      **不使用 client 端 lag**——consumer 死亡時 client 端 series 會消失而非增長）
- [ ] 9.5 PostgreSQL 連線數 > 80%、Longhorn > 85%、憑證 30 天到期、
      ArgoCD OutOfSync > 15min 等平台告警
- [ ] 9.6 每一條新增告警都補上 `promtool test rules` 測試

## 10. Dashboard as code（`k8s` repo，不經 CI）

- [ ] 10.1 自建 pipeline 漏斗 dashboard：依序呈現
      `scan → discovered → raw → normalized → events → notified`，讓任一段掉量一眼可見
- [ ] 10.2 加入 SLO-1／SLO-2 的達成率與 error budget 剩餘量面板
- [ ] 10.3 匯入社群 dashboard（JVM、Kafka、PostgreSQL、Node Exporter）並將其 JSON
      一併收進版控——不自己重畫
- [ ] 10.4 所有 dashboard 以帶 `grafana_dashboard: "1"` label 的 ConfigMap 形式進 `k8s` repo，
      由 Grafana sidecar 載入、ArgoCD 管理
- [ ] 10.5 驗證流程：UI 調整 → export JSON → 進 git → 確認 sidecar 載入後畫面一致
- [ ] 10.6 確認沒有任何 dashboard 只存在於 Grafana UI 而未進 git（drift 檢查）

## 11. 文件與收尾

- [ ] 11.1 更新 `docs/architecture.md`：Roadmap Phase 005 狀態、SLO 定義寫入「可觀測性」章節
- [ ] 11.2 更新 `homelab-infra/ARCHITECTURE.md`：告警路由與 SLO 說明
- [ ] 11.3 在 `TROUBLESHOOTING.md` 為每一條 critical 告警補上對應的處理章節
      （runbook 連結必須真的連得到內容，不能連到不存在的錨點）
- [ ] 11.4 實際計算一次當月 error budget 消耗，記錄總掃描次數 N 與實際失敗數，
      驗證 error budget 的計算流程可操作

## 12. 驗收

- [ ] 12.1 靜默失敗告警經實際觸發驗證，Discord 確實收到且 runbook 連結可用
- [ ] 12.2 `promtool test rules` 全數通過，涵蓋所有告警規則
- [ ] 12.3 SLO-1／SLO-2 在 Grafana 上有可視化的達成率與 error budget 剩餘量
- [ ] 12.4 Path A 與 Path B 的重疊指標數值一致
- [ ] 12.5 `DiscordNotifier` 的例外傳播測試通過（DLQ 行為未被破壞）
- [ ] 12.6 所有 dashboard 與告警規則都在 git 中，ArgoCD 顯示 Synced 無 drift
- [ ] 12.7 GitLab CI 全程只被觸發一次
