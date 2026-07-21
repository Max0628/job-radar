# Spec: dashboard-frontend

React + React Admin 前端，獨立部署服務，提供配置台與職缺瀏覽台。

## ADDED Requirements

### Requirement: 配置台（search-queries 資源）

系統 SHALL 提供 `search_queries` 的 List/Create/Edit/Delete 畫面。

#### Scenario: 列表畫面
- **WHEN** 使用者進入配置台
- **THEN** 顯示所有爬蟲設定，欄位含 source、keyword、location、maxPages、
  intervalMinutes、enabled

#### Scenario: 新增設定，source 為受限下拉選單
- **WHEN** 使用者點「新增」
- **THEN** 表單的 source 欄位是下拉選單，選項固定為 yourator/cakeresume
  （不開放自由輸入，見 design.md D5）

#### Scenario: 啟用/停用切換
- **WHEN** 使用者在列表或編輯畫面切換 `enabled`
- **THEN** 前端呼叫 PUT 更新該筆記錄

### Requirement: 職缺瀏覽台（jobs 資源，唯讀）

系統 SHALL 提供職缺的 List/Show 畫面，支援篩選與收藏。

#### Scenario: 篩選器
- **WHEN** 使用者在列表畫面輸入關鍵字、選擇地區、輸入薪資範圍、選擇職缺類型
- **THEN** 前端組成對應的 query params 呼叫 job-browse-api，即時更新列表

#### Scenario: 收藏按鈕
- **WHEN** 使用者在列表或詳情畫面點擊收藏／取消收藏
- **THEN** 前端呼叫對應的 favorites API，畫面即時反映收藏狀態

#### Scenario: 唯讀（不可編輯/刪除職缺）
- **WHEN** 使用者瀏覽職缺
- **THEN** 畫面不提供編輯或刪除職缺本身的功能（職缺資料只能由爬蟲管線寫入）

### Requirement: 前後端分離部署，api 端設定 CORS

系統 SHALL 以獨立服務部署前端，並在 `api` 模組設定 CORS 允許前端來源。

#### Scenario: CORS 設定
- **WHEN** 前端從其部署網域對 `api` 發送請求
- **THEN** `api` 回應正確的 CORS header，允許該來源的請求通過瀏覽器的同源限制

#### Scenario: 本機開發環境
- **WHEN** 開發者執行 `npm run dev`（Vite 開發伺服器）
- **THEN** 開發伺服器透過 proxy 設定轉發 API 請求給本機 `api` 服務，避免開發時的 CORS 問題
