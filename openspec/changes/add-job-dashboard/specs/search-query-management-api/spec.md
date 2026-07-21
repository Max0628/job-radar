# Spec: search-query-management-api

`api` 模組對 `search_queries` 表的 CRUD REST 端點，供 Dashboard 配置台使用。

## ADDED Requirements

### Requirement: 列出所有爬蟲設定

系統 SHALL 提供列出 `search_queries` 的端點，支援 React Admin 的分頁/排序慣例。

#### Scenario: 取得列表
- **WHEN** 前端 GET `/api/search-queries?_start=0&_end=20&_sort=id&_order=ASC`
- **THEN** 系統回傳該範圍的 `search_queries` 記錄（JSON 陣列）
- **AND** 回應 header 帶 `X-Total-Count`（總筆數，不受分頁影響）

### Requirement: 新增爬蟲設定

系統 SHALL 允許新增一組 `search_queries` 記錄。

#### Scenario: 成功新增
- **WHEN** 前端 POST `/api/search-queries`，body 含 `source`、`keyword`（可空字串）、
  `location`（可空）、`categories`（可空，字串陣列）、`maxPages`、`intervalMinutes`、`enabled`
- **AND** `source` 是已註冊的來源之一（`yourator` 或 `cakeresume`）
- **THEN** 系統寫入一筆新記錄，回傳 201 與該記錄（含產生的 id）

#### Scenario: source 不是已註冊的來源
- **WHEN** 前端 POST `/api/search-queries`，`source` 值不在 `{yourator, cakeresume}` 之內
- **THEN** 系統回傳 400，錯誤訊息說明合法的 source 值
- **COMMENT** 前端已用下拉選單限制輸入（見 design.md D5），後端仍需做基本驗證，
  防止繞過前端直接打 API 造成靜默不生效的設定

#### Scenario: Yourator 的 categories 只帶一個值
- **WHEN** 前端 POST `/api/search-queries`，`source="yourator"` 且 `categories` 陣列
  長度為 1
- **THEN** 系統仍接受這筆設定（不擋），但回應帶 `X-Warning` header，提醒單一分類值在
  Yourator API 上過濾行為不可靠（見 design.md D8 決策 1），建議至少帶 2 個分類值
- **COMMENT** 這不是硬性錯誤（400），因為使用者可能有意只想觀察某個分類的行為；
  Collector 端（`YouratorListScraper`）也會針對這個情況記錄警告 log

#### Scenario: 違反 (source, keyword) 唯一約束
- **WHEN** 前端 POST `/api/search-queries`，`source` + `keyword` 的組合已存在
  （已用真實請求驗證確實會發生：同一 source 的多列常常都用空字串 keyword，
  改以 categories 當主要篩選維度，容易撞到既有列）
- **THEN** 系統回傳 409，訊息說明是唯一約束衝突，不是伺服器錯誤
- **COMMENT** `(source, keyword)` 唯一約束是 V1 schema 時代的設計（當時假設每列 keyword
  都不同），categories 機制導入後這個約束的意義已經模糊，是否要調整（例如拿掉，或改成
  約束別的欄位組合）留待實際使用後再評估，見 design.md 的已知限制

### Requirement: 修改爬蟲設定

系統 SHALL 允許修改既有 `search_queries` 記錄的欄位。

#### Scenario: 成功修改
- **WHEN** 前端 PUT `/api/search-queries/:id`，body 含更新後的欄位
- **THEN** 系統更新該筆記錄，回傳 200 與更新後的完整記錄
- **AND** Collector 下一次排程檢查（`ScanScheduler.tick()`）會讀到新設定，不需重啟服務

#### Scenario: 修改不存在的 id
- **WHEN** 前端 PUT `/api/search-queries/:id`，該 id 不存在
- **THEN** 系統回傳 404

### Requirement: 刪除爬蟲設定

系統 SHALL 允許刪除 `search_queries` 記錄。

#### Scenario: 成功刪除
- **WHEN** 前端 DELETE `/api/search-queries/:id`
- **THEN** 系統刪除該筆記錄，回傳 200，body 為 `{"id": <刪除的 id>}`（不是空 body，
  理由見 job-favorites spec 的對應 scenario）
- **AND** 對應的 `scrape_cursors` 記錄一併刪除（避免孤兒記錄）
