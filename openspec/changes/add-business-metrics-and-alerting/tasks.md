# Tasks: add-business-metrics-and-alerting

> 前置：`add-platform-observability` 必須先完成（Prometheus 真的採集得到 job-radar 的指標）。
> **Java 變更只推送一次**，所有驗證在本機完成。`k8s` repo 的部分不經 CI，可先行迭代。

## 0. 前置確認

- [x] 0.1 核對過 `add-platform-observability` 的免費指標清單：JVM/HTTP/HikariCP/
      resilience4j 確實免費，但 **Kafka consumer 端指標完全不存在**（`KafkaConsumerConfig`
      手動 new `DefaultKafkaConsumerFactory` 繞過 Spring Boot 的 Micrometer 自動綁定），
      本 change 規劃的指標無重複
- [x] 0.2 確認 `collector`／`worker` 的 `build.gradle.kts` 已有
      `micrometer-registry-prometheus`，無需新增依賴

## 1. `k8s` repo 先行：不依賴 Java 變更的告警（不經 CI）

- [x] 1.1 `apps/job-radar/prometheus-rules.yaml` 新增三條 Path A 告警（實際檔名，
      不是原計畫的 `platform/prometheus-rules/job-radar-business.yaml`——放在
      job-radar 自己的 apps 目錄下更合理）
- [x] 1.2 `JobRadarSourceSilent` 帶活躍時段條件（`and on() (hour() < 15)`）——
      **這裡踩到一個坑**：`hour()` 是無 label 向量，跟帶 `source` label 的向量
      `and` 預設要求 label set 完全一致，沒加 `on()` 這條規則恆為空、永遠不會
      觸發，`promtool test rules` 第一次跑就抓到
- [x] 1.3 `JobRadarScanSuccessRateLow`（SLO-2 ≥ 95%）
- [x] 1.4 `JobRadarDlqNotEmpty`（DLQ 深度 > 0）
- [x] 1.5 `promtool check rules` 通過
- [x] 1.6 `promtool test rules` 涵蓋活躍/非活躍時段、94%/96% 成功率、DLQ 0→1，
      全部通過（見 `apps/job-radar/tests/`）
- [x] 1.7 dry-run 驗證後推送（commit `8ab9281`），ArgoCD 同步確認

## 2. Alertmanager 路由（`homelab-infra` + `k8s` repo）

- [x] 2.1 依 severity 分級路由至單一 Discord catch-all receiver（`AlertmanagerConfig`
      CRD，見 design.md「Alertmanager 路由實作記錄」——原計畫用 raw
      `alertmanager.config` 的 `webhook_url_file` 因 operator 版本限制不可行，
      改用 CRD 的 `secretKeyRef` 機制）
- [x] 2.2 所有告警 annotation 都有 `runbook_url`，且對應章節已寫進
      `homelab-infra/TROUBLESHOOTING.md`（含明確 `<a name>` anchor，不依賴
      GitHub markdown slug 猜測）
- [x] 2.3 group_wait/interval/repeat_interval 依 severity 分級（critical 快、
      Watchdog 24h 心跳）
- [x] 2.4 **端到端驗證：只做到「Alertmanager 真的呼叫 Discord API」這一步**。
      沒有真的 Discord webhook（`REPLACE_ME` placeholder），改用格式合法的假 URL，
      實測收到 Discord 真實 API 回應的 `404 Unknown Webhook`（預期行為，證明
      routing tree／receiver 比對／payload 組裝全部正確）與幾次 `429`。**真正
      送達 Discord 頻道這一步待使用者填入真的 webhook URL 後才能完成**
- [x] 2.5 `Watchdog` 改路由到同一個 discord receiver、repeat_interval 24h，
      不再丟進 `'null'`——這是明確的處置（接上），不是刻意忽略

## 3. `collector` 埋點（`job-radar` repo）

- [x] 3.1 `ScanService.runScan()`：`jobradar.scan{source,result}`
- [x] 3.2 `jobradar.jobs.discovered{source}`
- [x] 3.3 `jobradar.scan.duration{source}`（Timer）
- [x] 3.4 `YouratorListScraper.fetchPage()`：429 時記
      `jobradar.scrape.retry{source="yourator",reason="rate_limited"}`
- [x] 3.5 `CakeResumeListScraper` 確實有等價的 429 重試邏輯，同樣埋點
- [x] 3.6 確認 label 值域：只有 `source`／`result`／`reason`，無 `query_keyword`／
      `sourceJobId`／`url`／例外訊息

## 4. `worker` 埋點（`job-radar` repo）

- [x] 4.1 `NormalizerListener`：`jobradar.parse{source,result}`——**設計調整**：
      `RawPayloadParser.parse()` 實際上不會整筆回傳 null 或拋例外（欄位級降級如
      `postedAt` 已在別處處理），"failure" 改為涵蓋 try/catch 包住的整個
      parse+upsert 流程，捕捉非預期例外，計數後重新拋出
- [x] 4.2 `jobradar.events.published{source,type}`（新職缺 upsert 成功時）
- [x] 4.3 `DiscordNotifier.onEvent()`：`jobradar.notification{result}`
- [x] 4.4 推播成功後記錄 `jobradar.pipeline.latency`
- [x] 4.5 該 Timer 設定 `serviceLevelObjectives(Duration.ofMinutes(5))`
- [x] 4.6 `jobradar.pipeline.latency` 不含 `source` label

## 5. `DiscordNotifier` 錯誤處理（`job-radar` repo）— 高風險項

- [x] 5.1 try/catch 後 catch 區塊確實重新拋出（`throw e`）
- [x] 5.2 測試模擬 webhook 500 錯誤，斷言例外向上傳播（`DiscordNotifierTest.
      exceptionIsCountedButStillPropagates`）——**這個修正在真實環境也驗證過**：
      部署後送測試訊息，實測 log 顯示 `Error handler threw an exception` /
      `Seek to current after exception`，三次重試確實發生
      （`jobradar_notification_total{result="failure"}` = 4，精確對應
      「1 次初始 + 3 次重試」）
- [x] 5.3 建構子改注入 `RestClient.Builder`
- [x] 5.4 檢查過 `CakeResumeListScraper`／`YouratorDetailScraper`：兩者都已經是
      注入 builder，不需要改

## 6. 埋點測試（`job-radar` repo）

- [x] 6.1 `SimpleMeterRegistry` 測試涵蓋每個新 meter（`ScanServiceTest`、
      `NormalizerListenerTest`、`DiscordNotifierTest`，加上兩個 list scraper
      測試類各自的 rate-limit 測試）
- [x] 6.2 失敗路徑：`ScanServiceTest.failedScanRecordsFailureResultNotSuccess`、
      `NormalizerListenerTest.parserExceptionIsCountedAndRethrown`
- [x] 6.3 `DiscordNotifierTest.successfulNotificationRecordsSuccessAndPipelineLatency`
      驗證 latency timer 有記錄樣本

## 7. 本機驗證（推送前完成）

- [x] 7.1 `./gradlew test -PskipDockerTests` 全數通過（含既有測試，0 failures）
- [x] 7.2/7.3 未做本機 `bootRun`（collector/worker 需要真的 DB+Kafka 連線，
      本機沒有現成環境）——改以單元測試 + 部署後對真實叢集驗證取代，
      涵蓋面更完整（見 §8）
- [x] 7.4 單元測試已確認新 meter 的 label 只有 `source`／`result`／`type`／
      `reason`，無高基數風險
- [x] 7.5 單次 commit + push（`baabbc8`），CI 全程只觸發一次
      （test/build/package:collector/package:worker/deploy 皆綠燈，
      `rules: changes:` 正確判斷跳過 api/frontend）

## 8. 部署後驗證與 Path A 交叉比對

- [x] 8.1 部署後確認 `jobradar_parse_total`／`jobradar_events_published_total`／
      `jobradar_notification_total` 皆出現且數值正確（送測試訊息驗證，
      見 design.md 附錄）
- [ ] 8.2 `jobradar_scan_total` vs Path A 成功率交叉比對——**`jobradar_scan_*`
      系列指標要等下一個活躍時段（台灣 08:00–23:00）真的排程掃描過才會被
      Micrometer 註冊**，這次工作階段是凌晨進行，尚未產生，留給之後驗證
- [ ] 8.3 同上，`jobradar_jobs_discovered_total` 也要等真的掃描
- [x] 8.4 已驗證的部分（parse／notification）Path A／Path B 無重疊項目，
      不適用交叉比對；待 8.2/8.3 有資料後再確認

## 9. 依賴 Path B 的告警（`k8s` repo，不經 CI）

- [x] 9.1 `JobRadarPipelineLatencySLOBurnFast`/`Slow`（SLO-1）
- [x] 9.2 雙視窗設計（1h fast/warning、6h slow/info）
- [x] 9.3 `JobRadarNotificationFailureRateHigh`
- [x] 9.4 `JobRadarConsumerLagGrowing`（broker 端 `kafka_consumergroup_lag` +
      `deriv()`，非 client 端）
- [x] 9.5 `JobRadarPostgresConnectionsHigh` +
      `HomelabLonghornVolumeUsageHigh`／`HomelabCertificateExpiringSoon`／
      `HomelabArgoCDAppOutOfSync`（後三條放 `platform/prometheus-rules.yaml`）
- [x] 9.6 **每一條都補了 `promtool test rules` 測試，而且測試抓到 3 個真的
      永遠不會觸發的 bug**：
      - `JobRadarPipelineLatencySLOBurnFast`/`Slow`：除法左邊
        `{le="300"}`、右邊無此 label，預設 label matching 失敗，修法
        `ignoring(le)`
      - `JobRadarNotificationFailureRateHigh`：`result="failure"` 與
        `result="success"` 兩個不同 label 值的 series 直接相加同樣 match
        不到，改用 `sum()` 聚合掉該 label
      - `JobRadarPostgresConnectionsHigh`：`pg_stat_database_numbackends`
        帶 `datname`/`datid`、`pg_settings_max_connections` 沒有，修法
        `ignoring(datname,datid)`，額外對真實叢集的即時資料驗證過
      三者都是同一類「PromQL 算術運算子預設要求 label set 完全一致」的坑，
      詳見 design.md

## 10. Dashboard as code（`k8s` repo，不經 CI）

- [x] 10.1 `job-radar-pipeline.json`：漏斗（scan→discovered→normalized→
      events→notified，1h 增量 barchart）
- [x] 10.2 SLO-1／SLO-2 達成率 stat 面板（未做 error budget 剩餘量的獨立面板，
      SLO 達成率已可由使用者推算，留待之後有更多資料再細化）
- [ ] 10.3 社群 dashboard（JVM、Kafka、PostgreSQL、Node Exporter）**未匯入**——
      需要 Grafana UI 操作，這次 session 沒有瀏覽器可用，留給使用者手動匯入
- [x] 10.4 dashboard 以 `grafana_dashboard: "1"` label 的 ConfigMap 形式進
      `k8s` repo（`apps/job-radar/grafana-dashboard.yaml`）
- [x] 10.5 驗證流程：手動 apply → 用 Grafana API 確認 6 個 panel 註冊成功 →
      刪除手動版本 → commit 進 git → 由 ArgoCD 建立
- [x] 10.6 已確認沒有殘留手動建立、未進 git 的 dashboard

## 11. 文件與收尾

- [x] 11.1 `docs/architecture.md`：Roadmap Phase 005、可觀測性章節、SLO 定義、
      前置作業第 4 點的錯誤宣稱已修正
- [x] 11.2 `homelab-infra/ARCHITECTURE.md`：AlertmanagerConfig 路由與 SLO 說明
- [x] 11.3 `TROUBLESHOOTING.md` 為每條 critical 告警補上對應章節（含明確
      anchor），另外也補了 Longhorn snapshot 那個真實案例
- [ ] 11.4 **未做**：實際計算一個月的 error budget 消耗——這次工作階段只有
      幾小時的真實資料，量不出有意義的月度數字，留待累積更長時間的真實運行
      資料後再算

## 12. 驗收

- [ ] 12.1 靜默失敗告警**尚未**經過真實觸發驗證（需要真的等到 silent failure
      發生，或人工調低門檻——這次沒有刻意這樣做，因為已經有 `JobRadarDlqNotEmpty`
      提供了更好的真實案例，見下方）
- [x] 12.2 `promtool test rules` 全數通過，涵蓋 apps/job-radar 與 platform
      兩個目錄的全部 11 條告警規則
- [x] 12.3 SLO-1／SLO-2 在 Grafana `job-radar Pipeline` dashboard 上可視化
      （error budget 剩餘量的獨立面板未做，見 §10.2）
- [ ] 12.4 Path A 與 Path B 的重疊指標**尚未能完整比對**——等下一個活躍時段
      的真實排程掃描資料，見 §8.2/8.3
- [x] 12.5 `DiscordNotifier` 的例外傳播測試通過，且**在真實叢集也驗證過**
      （見 §5.2）
- [x] 12.6 所有 dashboard 與告警規則都在 git 中，ArgoCD 顯示 Synced 無 drift
- [x] 12.7 GitLab CI 全程只被觸發一次（commit `baabbc8`）

**額外驗收（非計畫項目，但值得記錄）**：

- [x] `JobRadarDlqNotEmpty` 上線第一天就是 `firing`——真實抓到
      `job-radar-discord` webhook 是 placeholder、已默默壞了 6+ 天的問題，
      比原計畫的「人工調低閾值驗證」更有說服力
- [x] `HomelabLonghornVolumeUsageHigh` 上線幾分鐘內進入 `pending`——真實抓到
      Prometheus 自己的 volume 因為一個 23 天未清的 snapshot 導致實際磁碟
      佔用超過邏輯容量（112.6%）
