# Spec: job-status-lifecycle

職缺狀態管理，為未來 closed sweep 預留欄位與邏輯。

## ADDED Requirements

### Requirement: 職缺狀態機

系統 SHALL 管理職缺的生命週期狀態，並為未來擴展預留。

狀態定義：
- **NEW**：職缺首次發現時的初始狀態
- **ACTIVE**：職缺被爬蟲再次發現（last_seen_at 更新）時保持的狀態
- **CLOSED**：職缺被標記為已下架（Phase 004 實作）

#### Scenario: 新職缺初始狀態為 NEW
- **WHEN** Normalizer 首次 INSERT 職缺到 jobs 表
- **THEN** status 欄位設為 'NEW'

#### Scenario: 職缺被重新發現時狀態轉為 ACTIVE
- **WHEN** Normalizer upsert 更新既有職缺的 last_seen_at
- **AND** 職缺當前 status = 'NEW' 或 'CLOSED'
- **THEN** 更新 status = 'ACTIVE'
- **COMMENT** Phase 002 中此欄位只被設置，不被查詢；推播邏輯基於 xmax，不基於 status

#### Scenario: Dashboard 查詢排除已關閉職缺
- **WHEN** API 層查詢職缺列表（未來 Dashboard 用）
- **THEN** 預設 WHERE status != 'CLOSED'
- **AND** 若使用者明確要求，可包含 CLOSED 職缺（用於歷史查看）

### Requirement: 狀態轉移記錄（未來擴展）

系統 SHALL 在 job_snapshots 表記錄狀態變化，供未來分析。

#### Scenario: 每次狀態變化記錄快照
- **WHEN** 職缺 status 發生變化（NEW → ACTIVE 或 ACTIVE → CLOSED）
- **THEN** 向 job_snapshots 表寫入新快照：
  - source、sourceJobId、status、scraped_at
  - jobs 表當前的所有欄位副本
- **AND** (source, sourceJobId, scraped_at) 唯一（append-only）

#### Scenario: 查詢職缺歷史
- **WHEN** 使用者查詢職缺的歷史版本
- **THEN** 查詢 job_snapshots，ORDER BY scraped_at，顯示歷次變化
- **COMMENT** Phase 003+ 實作（Dashboard 頁面）
