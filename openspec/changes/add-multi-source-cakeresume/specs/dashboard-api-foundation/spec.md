# Spec: dashboard-api-foundation

為未來 Dashboard 預留 API 層與 DB 層設計。Phase 002 先設計好，Phase 003 實作前端。

## ADDED Requirements

### Requirement: 職缺搜尋 API 端點

系統 SHALL 提供搜尋職缺的 REST API 端點（實作留給 Phase 003）。

#### Scenario: 按關鍵字搜尋
- **WHEN** 前端 GET `/api/jobs?query=後端工程師`
- **THEN** 系統查詢 jobs 表（WHERE status != 'CLOSED'），title / description 包含「後端工程師」
- **AND** 回傳職缺列表（分頁）：
  ```json
  {
    "total": 123,
    "page": 1,
    "pageSize": 20,
    "jobs": [
      {
        "id": "...",
        "source": "yourator",
        "sourceJobId": "41246",
        "title": "Lead Software Engineer",
        "company": "AIFIAN",
        "location": "台北市",
        "salaryMin": 1800000,
        "salaryMax": 2500000,
        "salaryCurrency": "TWD",
        "jobType": "full_time",
        "seniorityLevel": "mid_senior_level",
        "url": "https://...",
        "firstSeenAt": "2026-07-21T...",
        "lastSeenAt": "2026-07-21T..."
      },
      ...
    ]
  }
  ```

#### Scenario: 按地區篩選
- **WHEN** 前端 GET `/api/jobs?location=Taipei&query=DevOps`
- **THEN** 系統額外篩選 location 欄位包含「Taipei」的職缺

#### Scenario: 按薪資範圍篩選
- **WHEN** 前端 GET `/api/jobs?salaryMin=1500000&salaryMax=2500000`
- **THEN** 系統篩選 jobs.salaryMin >= 1500000 AND jobs.salaryMax <= 2500000 的職缺

#### Scenario: 按工作類型篩選
- **WHEN** 前端 GET `/api/jobs?jobType=full_time`
- **THEN** 系統篩選 jobs.job_type = 'full_time' 的職缺

### Requirement: 職缺詳情 API 端點

系統 SHALL 提供單一職缺詳情查詢。

#### Scenario: 查詢職缺完整資訊
- **WHEN** 前端 GET `/api/jobs/{id}`
- **THEN** 系統回傳職缺的詳細資訊：
  - 所有 jobs 表的欄位
  - 來自 raw_documents 的平台原始 payload（供展示平台特定欄位）
  - job_snapshots 的歷史版本列表（status 變化歷史）

#### Scenario: 查詢職缺歷史版本
- **WHEN** 前端 GET `/api/jobs/{id}/history`
- **THEN** 系統回傳該職缺的所有快照，ORDER BY scraped_at：
  ```json
  {
    "versions": [
      {
        "scrapedAt": "2026-07-21T10:00:00Z",
        "status": "NEW",
        "title": "...",
        "salaryMin": 1800000,
        "salaryMax": 2500000,
        ...
      },
      {
        "scrapedAt": "2026-07-21T12:00:00Z",
        "status": "ACTIVE",
        "title": "...",
        "salaryMin": 1800000,
        "salaryMax": 2500000,
        ...
      }
    ]
  }
  ```

### Requirement: DB 欄位支持 Dashboard 查詢

系統 SHALL 在 jobs 表新增欄位，支持 Dashboard 的篩選需求。

#### Scenario: 新欄位清單
- **WHEN** Normalizer 存取職缺時
- **THEN** 以下欄位可用於查詢或展示：
  - `employment_type`（VARCHAR）：Yourator 的 FULL_TIME；CakeResume 的 full_time
  - `seniority_level`（VARCHAR）：CakeResume 的 mid_senior_level；Yourator 無此欄位
  - `job_type`（VARCHAR）：同 employment_type（未來做欄位合併）
  - `lang_name`（VARCHAR）：CakeResume 的 English；Yourator 無此欄位
  - `min_work_exp_year`（INT）：CakeResume 的最低年資；Yourator 無此欄位
  - `number_of_openings`（INT）：CakeResume 的職位數；Yourator 無此欄位
  - 所有欄位可 NULL（允許某平台無此資訊）

#### Scenario: 索引以支持查詢效能
- **WHEN** Dashboard 查詢職缺時頻繁用到特定欄位
- **THEN** DB 中建立索引：
  - `(source, status)`：快速篩選「某平台的活躍職缺」
  - `(title)`：支持關鍵字搜尋（或用 LIKE / FULLTEXT）
  - `(job_type, seniority_level)`：組合篩選

### Requirement: raw_documents 保留平台原始數據

系統 SHALL 保留完整的原始 payload，以備未來 LLM 提取或狀態偵測使用。

#### Scenario: 查詢平台特定欄位
- **WHEN** Dashboard 需要展示某來源的特有欄位（如 Yourator 的 JSON-LD 或 CakeResume 的 lang_name）
- **THEN** 查詢 raw_documents.raw_payload（JSONB），用 PostgreSQL JSONB 查詢語法提取
- **EXAMPLE**：
  ```sql
  SELECT raw_payload->>'lang_name' FROM raw_documents 
  WHERE source = 'cakeresume' AND sourceJobId = '...'
  ```
