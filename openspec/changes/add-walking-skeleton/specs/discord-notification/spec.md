# Spec: discord-notification

## ADDED Requirements

### Requirement: 新職缺推播
Notifier SHALL 消費 `jobs.events` 的 NEW 事件並推送 Discord webhook，
embed 內容含職稱、公司、薪資（無則省略）、可點擊的職缺連結。

#### Scenario: 新職缺推播一次且僅一次
- **WHEN** 一筆新職缺完成入庫
- **THEN** Discord 頻道收到恰好一則對應推播；同職缺後續任何重爬/重放不再推播
- **驗證**：已用真實 webhook 對 5 筆真實 Yourator 新職缺跑過，Discord 頻道確認收到

#### Scenario: Discord 暫時不可用
- **WHEN** webhook 呼叫失敗
- **THEN** 依失敗隔離規則重試後進 dlq，不影響資料入庫（事件與入庫已解耦）
