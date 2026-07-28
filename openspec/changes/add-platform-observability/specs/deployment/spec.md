# Spec: deployment

## MODIFIED Requirements

### Requirement: 可觀測性最低限度
三個服務 SHALL 暴露 Prometheus metrics endpoint 並被既有 kube-prometheus-stack 抓取，
log 以結構化 JSON 輸出供 Loki 收集。

**本次修改的原因**：原條文（定義於 `add-walking-skeleton`）只描述期望狀態，
未規定如何確認。實際上 Service 因缺少 `metadata.labels` 而從未被 ServiceMonitor 選中，
此需求自 walking skeleton 上線後將近一個月處於未成立狀態而無人察覺。
新增可執行的驗證方式，使此需求不再能夠「看起來成立」。

#### Scenario: metrics 可見（修訂為可執行的驗證）
- **WHEN** 查詢 Prometheus `/api/v1/targets`（而非僅檢視 ServiceMonitor 資源是否存在）
- **THEN** 三個服務的 target 均出現且 `health="up"`、`lastError` 為空

#### Scenario: ServiceMonitor 存在不等於採集生效
- **WHEN** `kubectl get servicemonitors -n job-radar` 回傳資源存在
- **THEN** 此結果 MUST NOT 被當作採集已生效的證據；必須另外以
  `/api/v1/targets` 確認確有對應 target

#### Scenario: 結構化 log 仍持續成立
- **WHEN** 檢視三個服務的 log 輸出
- **THEN** 為 `LogstashEncoder` 產生的 JSON，可在 Loki 中以欄位查詢
  （此部分原本即已成立，本次僅確認未退化）
