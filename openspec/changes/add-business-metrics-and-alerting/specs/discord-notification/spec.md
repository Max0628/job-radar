# Spec: discord-notification

## MODIFIED Requirements

### Requirement: Discord 通知錯誤處理
新增推播成敗的可觀測性，但既有的錯誤處理語意 MUST 完全不變。

現行行為（`KafkaConsumerConfig.buildFactory`）：`DiscordNotifier.onEvent()` 對
`restClient.post()` 不做例外處理，例外向上傳播至 `DefaultErrorHandler`，
經 `FixedBackOff(1000L, 3L)` 重試三次後，由 `DeadLetterPublishingRecoverer`
投遞至 `jobs.events.dlq`。

#### Scenario: 計數不得改變例外傳播行為
- **WHEN** Discord webhook 呼叫失敗，且程式碼已加入計數用的 try/catch
- **THEN** 計數後 MUST 重新拋出原例外，使既有的三次重試與 DLQ 投遞行為完全不變

#### Scenario: 禁止吞掉例外
- **WHEN** 檢視 `DiscordNotifier` 的 catch 區塊
- **THEN** 不得有任何路徑在計數後正常返回——若例外被吞掉，訊息會被視為處理成功
  並 commit offset，該職缺永久遺失、DLQ 永遠為空，同時違反 D5 的 at-least-once 保證，
  且從外部完全觀察不出異常

#### Scenario: 失敗仍會進入 DLQ
- **WHEN** Discord webhook 連續三次呼叫失敗
- **THEN** 訊息出現在 `jobs.events.dlq`，且 `jobradar_notification_total{result="failure"}`
  已記錄該次失敗

### Requirement: 對外 HTTP 呼叫一律可被 instrument
所有對外部服務的 `RestClient` MUST 由注入的 `RestClient.Builder` 建立，
不得使用 `RestClient.builder()` 靜態工廠。

現況：`YouratorListScraper` 使用注入的 builder（因而具備自動 instrument），
`DiscordNotifier` 使用靜態工廠（因而完全沒有 client 端指標）——
同為對外 HTTP 呼叫卻有不同的可觀測性，且缺少的那一個正是使用者可感知的最後一哩路。

#### Scenario: Discord 呼叫納入自動 instrument
- **WHEN** `DiscordNotifier` 發出 webhook 請求
- **THEN** 產生 client 端 HTTP 指標，與 `YouratorListScraper` 的行為一致

#### Scenario: 為 tracing 預留
- **WHEN** `add-distributed-tracing` 上線後
- **THEN** 此呼叫自動產生 client span，不需要再修改一次 `DiscordNotifier`
