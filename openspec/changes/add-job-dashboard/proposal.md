# Proposal: add-job-dashboard

## Why

job-radar 目前只有 Discord 推播，職缺資料進了 DB 之後沒有任何查詢介面，爬蟲設定（`search_queries`）
只能手動改 DB。使用者要鎖定多個關鍵字方向（後端/DevOps/SRE/Infra/雲端/架構師）、篩選台北市各區、
篩選薪資區間，並且想在同一個地方收藏有興趣的職缺——這些都需要一個可查詢、可配置的 Dashboard。
對應 `docs/architecture.md` Roadmap Phase 003（REST API + 簡易前端看板）。

## What Changes

- **新增 REST API（`api` 模組）**：
  - `search_queries` CRUD 端點（配置台用）
  - `jobs` 唯讀查詢端點（支援關鍵字、地區、薪資範圍、職缺類型、來源篩選 + 分頁排序）
  - `favorites` CRUD 端點（收藏功能）
- **新增地區欄位並補齊 parser**：`jobs` 表加 `district`／`city` 欄位，Yourator（JSON-LD
  `jobLocation.address`）與 CakeResume（`locations` 陣列）的 parser 補上地區抽取邏輯
- **新增獨立前端服務**：React + React Admin，獨立 SPA、獨立部署（前後端分離，`api` 開 CORS）
- **新增 `favorites` 表**：單使用者，不需要 user_id
- **`search_queries` 種子資料擴充**：後端/DevOps/SRE/Infra/雲端/架構師等關鍵字（沿用既有
  keyword-only 爬蟲策略，地區篩選在查詢層做，不在爬蟲層做，見 design.md）

## Capabilities

### New Capabilities

- `search-query-management-api`：`api` 模組對 `search_queries` 表的 CRUD REST 端點
- `job-browse-api`：`api` 模組對 `jobs` 表的唯讀查詢端點（篩選/排序/分頁）
- `job-location-extraction`：從平台原始 payload 抽取地區資訊到可查詢欄位
- `job-favorites`：收藏功能（表 + API）
- `dashboard-frontend`：React Admin 前端，獨立部署服務

### Modified Capabilities

- `per-source-normalizer`（來自 `add-multi-source-cakeresume`）：Yourator/CakeResume 的
  RawPayloadParser 新增地區欄位抽取邏輯

## Impact

### Code Changes
- `api/`：新增 Controller/Service/Repository（search-queries、jobs、favorites 三組）、CORS 設定
- `worker/`：`YouratorRawPayloadParser`、`CakeResumeRawPayloadParser` 補地區抽取；`NormalizedJob`
  加地區欄位
- `common/`：`Job` domain record 加地區欄位（如需要）
- `frontend/`（新目錄）：React + React Admin 專案，獨立 `package.json`、Dockerfile（多階段建置：
  node 建置 → nginx 服務靜態檔）

### DB Changes
- `jobs` 表新增地區欄位（district/city，可 null）
- 新增 `favorites` 表
- `search_queries` 種子資料擴充多組關鍵字

### Deployment
- 新增一個 k8s Deployment + Service（frontend），跨 repo 工作，這次先不處理
- `.gitlab-ci.yml` 新增 frontend 的 build/test/image job（npm install → build → docker build →
  push），這次先不處理

## Non-goals（這次不做）

- 職缺關閉偵測（closed-sweep）——使用者明確表示要更謹慎設計，先擱置，之後再開新 change 討論
- 求職狀態追蹤（INTERESTED/APPLIED/INTERVIEWING/OFFER/REJECTED 這種完整流程）——先做簡單的
  favorite 布林收藏，狀態追蹤留給之後的 change
- 登入驗證——內網／個人使用，不做
- 爬蟲層的地區篩選（server-side location filter）——地區篩選改在查詢層做，見 design.md 的
  「爬蟲請求量不被地區維度放大」決策
- CI/CD 與 k8s repo 的實際部署設定——這次先把程式碼與本機驗證做完
