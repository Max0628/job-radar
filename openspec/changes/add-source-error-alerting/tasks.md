## 1. collector：三類錯誤分類

- [x] 1.1 `YouratorListScraper.fetchPage`：新增
      `catch (HttpClientErrorException.Forbidden | HttpServerErrorException.
      ServiceUnavailable e)` 分支，不重試、直接拋例外，並
      `meterRegistry.counter("jobradar.scrape.anomaly", "source", SOURCE, "reason",
      "blocked").increment()`
- [x] 1.2 `CakeResumeListScraper.searchPage`：同樣新增這個分支
- [x] 1.3 新增測試：403/503 情境下不重試（只打一次就失敗）、`blocked` anomaly 計數器
      正確增加（Yourator/CakeResume 各 2 個新測試，共 4 個，全過）

## 2. k8s repo：Prometheus 規則

- [x] 2.1 `prometheus-rules.yaml` 新增 `JobRadarSourceBlocked` 規則：
      `increase(jobradar_scrape_anomaly_total{reason="blocked"}[5m]) > 0`，
      `for: 0m`，`severity: critical`
- [x] 2.2 `prometheus-rules_test.yaml` 新增正案例（`blocked` counter 增加時觸發）與
      負案例（沒有 `blocked` 事件時不觸發）
- [x] 2.3 本機跑 `apps/job-radar/tests/run-tests.sh` 驗證——`check rules`（9 條規則）
      與 `test rules` 皆 SUCCESS

## 3. 驗證

- [x] 3.1 `./gradlew :collector:test` 全過（Yourator 9 個測試、CakeResume 6 個測試，
      皆含新增的 403/503 情境）
- [x] 3.2 `run-tests.sh` 全過
