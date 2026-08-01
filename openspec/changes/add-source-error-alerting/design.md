## Context

`YouratorListScraper`/`CakeResumeListScraper` 的 `fetchPage`/`searchPage` 目前用
`catch (HttpClientErrorException.TooManyRequests e)`（429）跟
`catch (ResourceAccessException e)`（I/O 逾時）兩類分別重試，其餘例外一律
`catch (Exception e)` 直接包裝拋出、不重試。403/503 目前會落進最後這個 catch-all，
行為上「不重試」剛好符合 D19 要的效果，**但沒有專屬的 anomaly reason 標籤**，
Prometheus 規則沒東西可以偵測。

## Goals / Non-Goals

**Goals:**
- 403/503 明確標記 `reason=blocked`，跟其他非預期錯誤（`reason` 不明確、原本就會失敗
  的 catch-all）區分開來
- Prometheus 規則能在單次 `blocked` 事件發生時就觸發，不用等累積或持續一段時間

**Non-Goals:**
- 不改變 429/5xx/逾時的既有重試行為

## Decisions

**用 `HttpClientErrorException.Forbidden`/`HttpClientErrorException.ServiceUnavailable`
明確 catch，不是判斷 status code 數字**
Spring 的 `RestClient` 對 4xx/5xx 回應預設會拋出 `HttpClientErrorException`/
`HttpServerErrorException` 的具體子類別（403→`Forbidden`、503 屬於
`HttpServerErrorException.ServiceUnavailable`），直接 catch 具體型別，跟現有
`HttpClientErrorException.TooManyRequests` 的寫法一致，不用手動比對 `getStatusCode()`
數字。

**新的 catch 分支要放在 `TooManyRequests`/`ResourceAccessException` 之前**
`Forbidden`/`ServiceUnavailable` 不是這兩個既有分支的子類別，理論上放前後都不影響
比對結果，但放在前面能讓「不重試」的分支在閱讀順序上先被看到，跟 D19 文件裡「先講
不重試那類」的敘事順序一致。

**`JobRadarSourceBlocked` 規則用 `for: 0m`（單次即觸發），不像其他規則要求持續一段
時間**
D19 明確要求「單次即告警」，理由是重試機制已經先濾掉偶發抖動——`blocked` 這個 reason
本身就代表「未重試、確定是疑似風控」，不需要再靠告警規則的持續時間去二次過濾雜訊。

## Risks / Trade-offs

- **[風險] 403/503 有可能是暫時性的伺服器問題，不一定真的是風控**
  → 接受：寧可多告警、少漏看——D19 已經權衡過這個不對稱（見 architecture.md D19
  「風險不對稱」段落），告警成本遠低於誤判成「一般錯誤」重試、被判定更高風險的成本
