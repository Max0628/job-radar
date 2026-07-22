# Spec: job-browse-api

## MODIFIED Requirements

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
- **WHEN** 前端 GET `/api/jobs?_start=0&_end=20&_sort=postedAt&_order=DESC`
- **THEN** 系統回傳該範圍、依指定欄位排序的結果
- **AND** 回應 header 帶 `X-Total-Count`（篩選後的總筆數）
- **AND** `postedAt` 為可排序欄位之一（映射到 `posted_at` 欄位）

#### Scenario: 依平台真實日期排序，null 值排在最後
- **WHEN** 前端 GET `/api/jobs?_sort=postedAt&_order=DESC`（未帶 `postedAt` 的職缺與已帶值的職缺混雜）
- **THEN** 系統依 `posted_at` 由新到舊排序
- **AND** `posted_at` 為 null 的職缺一律排在結果最後面，不會出現在有值的職缺之前

#### Scenario: 未指定排序時的預設行為
- **WHEN** 前端 GET `/api/jobs`（未帶 `_sort`/`_order` 參數）
- **THEN** 系統預設依 `postedAt` 由新到舊排序（`posted_at DESC NULLS LAST`）

### Requirement: 單筆職缺詳情查詢

系統 SHALL 提供查詢單筆職缺完整資訊的端點。

#### Scenario: 查詢存在的職缺
- **WHEN** 前端 GET `/api/jobs/:id`
- **THEN** 系統回傳該職缺的所有欄位，含是否被收藏（`isFavorited`）
- **AND** 回應包含 `postedAt` 欄位（平台回報的真實職缺日期，可能為 null）

#### Scenario: 查詢不存在的職缺
- **WHEN** 前端 GET `/api/jobs/:id`，id 不存在
- **THEN** 系統回傳 404

## ADDED Requirements

### Requirement: 正規化階段擷取平台真實日期

系統 SHALL 在 worker 正規化職缺資料時，嘗試從來源平台的原始資料擷取真實的職缺日期，寫入 `jobs.posted_at`。

#### Scenario: Yourator 職缺成功解析 datePosted
- **WHEN** worker 正規化一筆 Yourator 職缺，其 detail 頁 JSON-LD 含有效的 `datePosted` 欄位
- **THEN** 系統解析該日期並寫入 `posted_at`

#### Scenario: CakeResume 職缺成功解析 content_updated_at
- **WHEN** worker 正規化一筆 CakeResume 職缺，其 search API 回應含有效的 `content_updated_at` 欄位
- **THEN** 系統解析該日期並寫入 `posted_at`

#### Scenario: 日期欄位缺失或格式無法解析
- **WHEN** worker 正規化一筆職缺，來源資料的日期欄位缺失或格式不符預期
- **THEN** 系統將 `posted_at` 寫為 null，記錄 warning log
- **AND** 正規化流程不中斷，其餘欄位正常寫入

#### Scenario: 既有職缺重新被掃描，日期解析結果與前次不同
- **WHEN** 一筆已存在的職缺被重新掃描，這次解析出的 `posted_at` 與資料庫既有值不同
- **THEN** 系統以本次解析結果覆蓋既有值（比照 `title`/`company` 等欄位的 upsert 行為）
