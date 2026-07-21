# Spec: per-source-normalizer

Normalizer 按來源路由到不同的 parser，處理平台差異。

## ADDED Requirements

### Requirement: Normalizer 能路由到 per-source parser

系統 SHALL 根據 RawEnvelope 的 source 欄位，路由到對應的 RawPayloadParser，將原始 payload 轉成 NormalizedJob。

#### Scenario: Yourator 職缺路由到 YouratorRawPayloadParser
- **WHEN** Normalizer 消費一筆 RawEnvelope，source = "yourator"
- **THEN** 路由到 YouratorRawPayloadParser
- **AND** Parser 從 JSON-LD payload 提取：
  - title（來自 title 欄位）
  - company（來自 hiringOrganization.name）
  - salaryMin / salaryMax（來自 baseSalary.value.minValue/maxValue，若不存在則 null）
  - salaryCurrency（來自 baseSalary.currency）
  - description（來自 description，HTML 格式）
  - url（來自 RawEnvelope.url）
  - employment_type（來自 employmentType）
- **AND** 回傳 NormalizedJob 物件

#### Scenario: CakeResume 職缺路由到 CakeResumeRawPayloadParser
- **WHEN** Normalizer 消費一筆 RawEnvelope，source = "cakeresume"
- **THEN** 路由到 CakeResumeRawPayloadParser
- **AND** Parser 從 search result payload 提取：
  - title
  - company（來自 page.name）
  - salaryMin / salaryMax（來自 salary.min/max，若不存在則 null）
  - salaryCurrency（來自 salary.currency）
  - description（來自 description）
  - url（組合 page.path 成 https://cake.me/jobs/{path}）
  - job_type（來自 job_type，例 full_time）
  - seniority_level（來自 seniority_level，例 mid_senior_level）
  - lang_name（來自 lang_name）
  - min_work_exp_year（來自 min_work_exp_year）
- **AND** 回傳 NormalizedJob 物件

#### Scenario: 未知來源採用預設處理
- **WHEN** Normalizer 消費 RawEnvelope，source 不在已註冊的 parser 清單
- **THEN** 發送告警／DLQ 訊息，不進行 upsert

### Requirement: 冪等 upsert + NEW event 判斷

系統 SHALL 執行冪等 upsert，並根據是否新插入決定發送 NEW event。

#### Scenario: 新職缺觸發 NEW event
- **WHEN** Normalizer upsert 到 jobs 表，該 (source, sourceJobId) 組合不存在
- **THEN** 執行 INSERT，`xmax = 0`
- **AND** 將職缺 status 設為 'NEW'
- **AND** 發送一筆 JobEventEnvelope 到 `jobs.events` topic：
  - eventType = "NEW"
  - sourceJobId、source、title、company、url
  - 其他 NormalizedJob 欄位

#### Scenario: 重複見到的職缺不觸發 NEW event
- **WHEN** Normalizer 消費第二筆（或之後）同 (source, sourceJobId) 的 RawEnvelope
- **THEN** 執行 UPDATE（只改 last_seen_at），`xmax > 0`
- **AND** 不發 event（職缺已存在）
- **AND** 職缺 status 保持 ACTIVE（或既有狀態）

#### Scenario: 併發重複訊息的處理
- **WHEN** Kafka 送出兩筆相同的 RawEnvelope（at-least-once 語義）
- **THEN** 第一筆 upsert INSERT 新職缺，發送 NEW event
- **THEN** 第二筆 upsert UPDATE，不發 event（冪等性保證）

### Requirement: 原始 payload 保存

系統 SHALL 將完整的原始 payload 保存到 raw_documents 表，供未來重放或分析。

#### Scenario: raw_documents 記錄
- **WHEN** Normalizer 處理一筆 RawEnvelope
- **THEN** 向 raw_documents 表寫入：
  - source
  - sourceJobId
  - raw_payload（RawEnvelope 的完整 payload，JSON 格式）
  - scraped_at（來自 RawEnvelope）
- **AND** (source, sourceJobId, scraped_at) 做 unique constraint，重複 insert-ignore
