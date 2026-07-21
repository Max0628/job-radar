# Spec: job-browse-api

`api` 模組對 `jobs` 表的唯讀查詢端點，供 Dashboard 職缺瀏覽台使用。

## ADDED Requirements

### Requirement: 職缺列表查詢，支援多維度篩選

系統 SHALL 提供查詢職缺列表的端點，支援關鍵字、地區、薪資範圍、職缺類型、來源平台篩選。

#### Scenario: 關鍵字搜尋
- **WHEN** 前端 GET `/api/jobs?q=後端工程師`
- **THEN** 系統回傳 `title` 或 `company` 包含該關鍵字的職缺（不分大小寫）

#### Scenario: 地區篩選
- **WHEN** 前端 GET `/api/jobs?district=信義區`
- **THEN** 系統回傳 `district` 欄位符合的職缺
- **AND** 若 `district` 為 null（該筆職缺沒有區級資料），不出現在結果中

#### Scenario: 城市篩選（district 資料不足時的退路）
- **WHEN** 前端 GET `/api/jobs?city=臺北市`
- **THEN** 系統回傳 `city` 欄位符合的職缺（涵蓋範圍比 district 篩選寬，因為部分職缺
  只有城市級資料）

#### Scenario: 薪資範圍篩選
- **WHEN** 前端 GET `/api/jobs?salaryMin=1000000&salaryMax=2000000`
- **THEN** 系統回傳 `salary_min >= 1000000 AND salary_max <= 2000000` 的職缺
- **AND** 薪資為 null（面議）的職缺不出現在結果中（除非未帶薪資篩選參數）

#### Scenario: 職缺類型與來源篩選
- **WHEN** 前端 GET `/api/jobs?jobType=full_time&source=cakeresume`
- **THEN** 系統回傳對應欄位符合的職缺

#### Scenario: 排除已關閉職缺（預設行為）
- **WHEN** 前端 GET `/api/jobs`（未帶 status 參數）
- **THEN** 系統預設排除 `status = 'CLOSED'` 的職缺
- **COMMENT** 目前系統不會真的把任何職缺標記 CLOSED（closed-sweep 邏輯未實作，見
  proposal.md non-goals），此規則是為未來相容預留，不影響現況查詢結果

#### Scenario: 分頁與排序
- **WHEN** 前端 GET `/api/jobs?_start=0&_end=20&_sort=lastSeenAt&_order=DESC`
- **THEN** 系統回傳該範圍、依指定欄位排序的結果
- **AND** 回應 header 帶 `X-Total-Count`（篩選後的總筆數）

### Requirement: 單筆職缺詳情查詢

系統 SHALL 提供查詢單筆職缺完整資訊的端點。

#### Scenario: 查詢存在的職缺
- **WHEN** 前端 GET `/api/jobs/:id`
- **THEN** 系統回傳該職缺的所有欄位，含是否被收藏（`isFavorited`）

#### Scenario: 查詢不存在的職缺
- **WHEN** 前端 GET `/api/jobs/:id`，id 不存在
- **THEN** 系統回傳 404
