# Spec: duplicate-prevention

推播去重，確保同一職缺不會在短時間內被推多次。

## ADDED Requirements

### Requirement: NEW event 只在真正新插入時發送

系統 SHALL 只在職缺首次插入到 jobs 表時發送 NEW event，重複見到不推播。

#### Scenario: 首輪發現職缺時推播
- **WHEN** Normalizer 為職缺 (source="yourator", sourceJobId="41246") 首次 upsert
- **THEN** INSERT 成功，`xmax = 0`
- **AND** 發送 JobEventEnvelope 到 `jobs.events`，eventType = "NEW"
- **AND** Notifier 消費該 event，推送 Discord embed

#### Scenario: 第二輪重複見到不推播
- **WHEN** 下一輪爬蟲（例 2 小時後）Collector 再次發現同一職缺 (yourator, 41246)
- **AND** Normalizer 執行 upsert UPDATE（jobs 表該記錄已存在）
- **THEN** UPDATE 成功，`xmax > 0`
- **AND** 不發 event
- **AND** Notifier 無訊息收到，不推播

#### Scenario: 多來源同職缺各推一次（符合預期）
- **WHEN** 同一職缺在 Yourator 和 CakeResume 都出現
- **THEN** Normalizer 為 (yourator, 41246) 和 (cakeresume, 12345) 各 INSERT
- **AND** 各發一次 NEW event → Notifier 各推一次 Discord
- **COMMENT** 此為符合 v1 預期行為，跨平台去重留給 Phase 006+

### Requirement: 推播紀錄的真相來源是 jobs 表狀態

系統 SHALL 不維護額外的「推播紀錄表」或「去重 cache」，以 jobs 表的 xmax 為準。

#### Scenario: Kafka 訊息重複時的冪等處理
- **WHEN** Kafka 因故障重送同一筆 RawEnvelope（at-least-once 語義）
- **AND** Normalizer 執行 upsert，jobs 表該記錄已存在（首次 upsert 已做過）
- **THEN** UPDATE 執行（xmax > 0），不發 event
- **AND** 即使 Notifier 因故障重啟、重新消費 events topic，也只會看到第一次的 NEW event
- **COMMENT** 冪等性保證：資料庫狀態是一致的，推播邏輯不因重複訊息而改變

### Requirement: NEW event 發送時機明確

系統 SHALL 在 Normalizer upsert 後立即檢查 xmax，據此決定是否發 event。

#### Scenario: 事務內原子性
- **WHEN** Normalizer 執行：
  ```sql
  INSERT INTO jobs (...) VALUES (...)
  ON CONFLICT (source, sourceJobId) DO UPDATE SET last_seen_at = now()
  RETURNING (xmax = 0) AS is_new
  ```
- **THEN** 單一事務內完成，不存在競態
- **AND** 根據 is_new 判斷是否發 Kafka event
- **COMMENT** 若要更嚴謹（transactional outbox），見 D11
