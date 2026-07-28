# Spec: alerting

## ADDED Requirements

### Requirement: SLO-1 Pipeline 延遲
系統 SHALL 定義並量測：99% 的職缺事件，從 `scrapedAt` 到 Discord 推播成功耗時 < 5 分鐘。

#### Scenario: SLI 可計算
- **WHEN** 查詢 SLO-1 的達成率
- **THEN** 由 `jobradar_pipeline_latency_seconds` 的 5 分鐘 bucket 計數除以總計數得出，
  為精確計數而非分位數估算

#### Scenario: 門檻寬鬆以確保零誤報
- **WHEN** pipeline 在正常狀態運作（秒級完成）
- **THEN** SLO-1 不應違反；違反時必然對應真實的阻塞（consumer lag 累積、外部 API 異常
  緩慢、Discord 限流），而非正常波動

### Requirement: SLO-2 掃描成功率
系統 SHALL 定義並量測：每個來源每日應執行的掃描中，成功完成比例 ≥ 95%。

#### Scenario: 不依賴應用埋點即可成立
- **WHEN** `add-platform-observability` 完成而本 change 的埋點尚未上線
- **THEN** SLO-2 已可由 Path A 的 `scrape_runs` 聚合指標計算，不需等待 Path B

#### Scenario: 容許外部平台的正常失敗
- **WHEN** 爬取對象偶發 429 或暫時性 5xx
- **THEN** 5% 的失敗額度足以吸收，不觸發違約；但持續性失敗仍會使成功率跌破門檻

### Requirement: Error budget 作為工程決策依據
系統 SHALL 可計算並呈現 SLO 的 error budget 剩餘量。其用途 MUST 定位為資源分配的仲裁，
而非告警門檻。

#### Scenario: budget 耗盡的意義
- **WHEN** 某月 SLO-2 的 error budget 消耗超過額度
- **THEN** 此結果代表爬蟲穩定性需要投入工程時間修復，優先於新增來源或功能

### Requirement: 靜默失敗告警獨立於 SLO
「某來源在過去 6 小時發現 0 筆職缺」SHALL 為獨立的 `severity: critical` 告警，
不佔用任何 SLO 的 error budget。

#### Scenario: 語意區隔
- **WHEN** 系統完全停止產出價值
- **THEN** 此情境由獨立告警捕捉，而非按比例扣減 error budget——
  停止運作不是「品質下降」，其 budget 消耗語意應為立即耗盡而非漸進

#### Scenario: 非活躍時段不誤報
- **WHEN** 時間落在台灣時間 23:00–08:00（`ScanScheduler.isWithinActiveHours()` 不掃描的時段）
- **THEN** 即使發現數為 0，告警 MUST NOT 觸發

### Requirement: 告警規則必須有單元測試
每一條 PrometheusRule SHALL 具備對應的 `promtool test rules` 測試，
涵蓋應觸發與不應觸發兩種情境。

#### Scenario: 靜默失敗告警的測試
- **WHEN** 執行 `promtool test rules`
- **THEN** 測試涵蓋「活躍時段連續 6h 為 0 → firing」與
  「凌晨時段連續 6h 為 0 → 不 firing」兩個案例

#### Scenario: 測試存在的理由
- **WHEN** 告警規則存在語法或邏輯錯誤
- **THEN** 由測試在部署前捕捉——告警規則平時不執行，寫錯不會有任何徵兆，
  直到真正出事那天它沒有響

### Requirement: 告警必須經實際觸發驗證
每一條 `severity: critical` 的告警 SHALL 在上線後經過至少一次實際觸發，
確認完整通知路徑可用。

#### Scenario: 端到端通知驗證
- **WHEN** 暫時調整門檻使告警必然觸發
- **THEN** Discord 確實收到訊息，內容可讀，runbook 連結可點擊且指向存在的內容

### Requirement: 告警需附 runbook
所有告警的 annotation SHALL 包含指向處理說明的連結。

#### Scenario: runbook 連結有效
- **WHEN** 點擊告警中的 runbook 連結
- **THEN** 連結指向 `homelab-infra/TROUBLESHOOTING.md` 中實際存在的對應章節，
  而非不存在的錨點

### Requirement: 對症狀告警而非對原因告警
告警清單 MUST 只包含「需要人立即採取行動」的項目。

#### Scenario: 排除純資源指標
- **WHEN** 檢視告警清單
- **THEN** 不含「CPU > 80%」這類條件——資源使用率高本身可能完全無害，
  真正有害的情況會透過延遲或失敗率呈現

#### Scenario: 可行動性檢驗
- **WHEN** 評估是否新增一條告警
- **THEN** 若無法回答「收到之後我現在要做什麼」，則該項目應呈現於 dashboard 而非成為告警

### Requirement: Consumer lag 告警使用 broker 端數據
consumer lag 相關告警 MUST 以 kafka-exporter 提供的 broker 端數據為依據，
不得使用 Spring Kafka client 自身回報的 lag。

#### Scenario: consumer 完全不存在時仍能告警
- **WHEN** `worker` 因故完全停止運行
- **THEN** 告警仍能觸發——client 端 lag 在此情境下 time series 會消失而非增長，
  以其撰寫的告警規則永遠不會觸發

### Requirement: 監控系統自身的存活可被偵測
kube-prometheus-stack 內建的 `Watchdog` 告警 SHALL 有明確處置：
接上外部檢查作為 dead man's switch，或在文件中記錄為刻意忽略並說明理由。

#### Scenario: 監控失效不得無聲無息
- **WHEN** Prometheus 或 Alertmanager 本身失效
- **THEN** 存在一個不依賴該系統的途徑可以察覺——否則上述所有告警等同不存在

### Requirement: 告警與 dashboard 全部進版控
所有 PrometheusRule 與 Grafana dashboard SHALL 存在於 `k8s` repo 並由 ArgoCD 同步。

#### Scenario: Dashboard as code
- **WHEN** 需要新增或修改 dashboard
- **THEN** 流程為「Grafana UI 調整 → export JSON → 寫進 git → 由帶
  `grafana_dashboard: "1"` label 的 ConfigMap 經 sidecar 載入」

#### Scenario: 無 drift
- **WHEN** 檢查 Grafana 中的 dashboard 清單
- **THEN** 不存在任何只在 UI 中建立而未進 git 的 dashboard

### Requirement: Pipeline 漏斗可視化
系統 SHALL 提供一個呈現各階段流量的 dashboard，使任一階段的掉量可被直接觀察。

#### Scenario: 掉量定位
- **WHEN** 某一階段的處理量明顯低於前一階段
- **THEN** 漏斗圖上該段落差可直接看出，不需逐一查詢個別指標
