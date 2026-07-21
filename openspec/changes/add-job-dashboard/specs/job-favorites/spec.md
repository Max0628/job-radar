# Spec: job-favorites

收藏功能：單使用者，同一個 Dashboard app 內，收藏/取消收藏職缺。

## ADDED Requirements

### Requirement: 收藏職缺

系統 SHALL 允許將職缺加入收藏。

#### Scenario: 成功收藏
- **WHEN** 前端 POST `/api/favorites`，body 含 `source`、`sourceJobId`
- **THEN** 系統寫入一筆 favorites 記錄，回傳 201
- **AND** 重複收藏同一筆職缺（相同 source + sourceJobId）不報錯，回傳既有記錄（冪等）

### Requirement: 取消收藏

系統 SHALL 允許取消收藏。

#### Scenario: 成功取消收藏
- **WHEN** 前端 DELETE `/api/favorites/:id`
- **THEN** 系統刪除該筆記錄，回傳 200，body 為 `{"id": <刪除的 id>}`
- **COMMENT** 不是空 body（204）——`ra-data-json-server` 這個 data provider 會讀 DELETE
  回應的 body 當作操作結果，空 body 在前端可能解析成 `undefined`，讓 React Admin 的
  列表快取更新出問題（實作階段串接前端時發現，見 add-job-dashboard/design.md）

### Requirement: 查詢收藏列表

系統 SHALL 提供收藏列表查詢端點。

#### Scenario: 列出所有收藏
- **WHEN** 前端 GET `/api/favorites`
- **THEN** 系統回傳所有收藏記錄（僅 favorites 表本身欄位，不 join `jobs` 表）
- **AND** 回應帶 `X-Total-Count` header（即使目前前端不一定會把這個端點做成獨立的
  React Admin List 頁面，仍照 data provider 慣例回這個 header，避免之後真的要加
  「我的收藏」頁面時還要回頭補）
- **COMMENT** 原計畫是 join jobs 表回傳職缺基本資訊，實作階段簡化為只回 favorites
  表本身欄位（source/sourceJobId/createdAt），避免把兩個 repository 耦合在一起；
  前端若要顯示職缺標題等資訊，用 sourceJobId 另外查 `/api/jobs`

### Requirement: 職缺查詢結果標記收藏狀態

系統 SHALL 在 job-browse-api 的回應中標記每筆職缺是否已收藏。

#### Scenario: 列表與詳情皆帶收藏狀態
- **WHEN** 前端查詢 `/api/jobs` 或 `/api/jobs/:id`
- **THEN** 回應的每筆職缺物件含 `isFavorited: boolean`
