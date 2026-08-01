## Why

`docs/architecture.md` D19 已定案：任何來源任一次掃描失敗（重試過仍失敗）應該單次即觸發
告警，且錯誤要分三類處理（疑似風控/一般性暫時錯誤/其他）。目前 Yourator/CakeResume 的
重試邏輯只有兩類（429、I/O 逾時都重試，其他一律直接失敗），沒有「疑似風控不重試」這個
分支，也沒有對應的 Prometheus 告警規則——現有的 `JobRadarSourceSilent`（6h 靜默）反應
太慢，不符合「單次即知道」的需求。

## What Changes

- `YouratorListScraper`/`CakeResumeListScraper` 的 `fetchPage`/`searchPage` 重試邏輯
  新增第三類分支：403/503 → 不重試，直接拋例外並標記 `reason=blocked`
- k8s repo `prometheus-rules.yaml` 新增規則 `JobRadarSourceBlocked`：偵測到
  `reason=blocked` 的 anomaly 計數器增加就觸發，不像其他規則需要持續一段時間
- `prometheus-rules_test.yaml` 補上對應的 promtool 正/負案例測試

## Non-Goals

- 不含 104——104 是全新來源，這套三類分類會直接寫進它的 scraper（見
  `add-104-source`），不需要像 Yourator/CakeResume 這樣「補上去」
- 不改變既有的 429/5xx/逾時重試邏輯本身（只是新增一個分支，不動原本兩類）
- 不處理 Alertmanager 的 routing/receiver 設定（`k8s/platform/alertmanager-config.yaml`
  已經有 `severity: critical` 的快速路由規則可以直接沿用，不用新增）

## Capabilities

### New Capabilities
- `source-blocked-detection`：涵蓋 403/503 這類疑似風控錯誤的偵測、不重試、告警行為

### Modified Capabilities
（無——`openspec/specs/` 目前的 `scan-scheduling`/`scan-summary-reporting` 都是不同
capability，這次不修改它們）

## Impact

- `collector` 模組：`YouratorListScraper`、`CakeResumeListScraper`
- `k8s` repo（另一個 repo，見 `docs/architecture.md`「相關位置」）：
  `apps/job-radar/prometheus-rules.yaml`、`apps/job-radar/tests/prometheus-rules_test.yaml`
- 不影響 `worker`/`api`/`frontend`
