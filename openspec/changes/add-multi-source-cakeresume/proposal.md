# Proposal: add-multi-source-cakeresume

## Why

Phase 001 成功驗證了單一來源（Yourator）的完整爬蟲流程。Phase 002 要加入 CakeResume，
並藉此機會確立「多來源支持的架構」，為未來加第三、第四個來源打基礎。
同時，現有推播邏輯（無篩選、每個新職缺都推）在多來源後會變得很吵，
DB 層也需要為未來的 Dashboard 查詢做好準備（支持搜尋、篩選、顯示職缺歷史）。

## What Changes

- **新增 CakeResume Collector adapter**：負責打 CakeResume search API，發現職缺
- **改造 Fetcher 層**：支援 per-source 邏輯（Yourator 打 detail 頁面、CakeResume no-op）
- **改造 Normalizer 層**：支援 per-source routing（根據 source 欄位路由到對應的 parser）
- **新增 CakeResumeRawPayloadParser**：從 CakeResume 的 search response 提取標準欄位
- **推播去重**：同一職缺短時間內不要重複推（用 jobs 表的 upsert 判斷 NEW event）
- **職缺狀態管理**：jobs 表加 status 欄位（NEW → ACTIVE），為未來 CLOSED 偵測預留空間
- **DB 設計優化**：jobs 表加欄位以支持 Dashboard 查詢（employment_type、seniority_level、lang_name 等）
- **DiscoveredEnvelope 擴充**：加 `needsDetail` 標記，告訴 Fetcher 這個職缺要不要再打 API

## Capabilities

### New Capabilities

- `cakeresume-adapter`：支援 CakeResume 平台的爬蟲適配器（list scraper + payload parser）
- `multi-source-fetcher`：per-source 決定是否需要打 detail API（Yourator 打、CakeResume 不打）
- `per-source-normalizer`：根據 source 路由到不同的 parser，處理平台差異
- `job-status-lifecycle`：職缺狀態管理（NEW、ACTIVE、CLOSED），供未來 closed sweep 使用
- `duplicate-prevention`：推播去重邏輯（避免短時間內推同一職缺多次）
- `dashboard-api-foundation`：DB 層與 API 層的設計，為 Dashboard 查詢預留接口

### Modified Capabilities

- `kafka-envelope`：加 `needsDetail` 欄位到 DiscoveredEnvelope，傳達平台特性
- `jobs-table-schema`：新增 status、employment_type、seniority_level、lang_name 等欄位，支持 Dashboard

## Impact

### Code Changes
- `collector/`：新增 `adapters/cakeresume/`；改造 Scheduler 支持多個 adapter
- `worker/`：Fetcher consumer 改成 per-source router；Normalizer 改成 per-source router
- `common/`：DiscoveredEnvelope 加欄位；新增 CakeResumeRawJobData 類型定義

### DB Changes
- `jobs` 表：加 `status`（enum：NEW/ACTIVE/CLOSED）、`employment_type`、`seniority_level`、`lang_name`、`number_of_openings`、`unique_impressions_count` 等欄位（可 null）
- Flyway 新 migration：新增欄位與索引

### Testing
- 單元測試：CakeResumeListScraper（mock search API response）、CakeResumeRawPayloadParser（JSON 解析）
- 整合測試：Fetcher 路由邏輯、Normalizer 冪等性（兩個來源各 10 筆）

### Deployment
- 無需改 k8s manifests（Collector pod 內容變了，但部署結構一樣）
- 無需改 CI/CD（同一個 image build）

## Non-goals（Phase 002 不做）

- 跨平台職缺合併去重（Yourator + CakeResume 同一職缺各推一次是可接受的）
- 職缺關閉偵測（CLOSED sweep）——欄位預留、邏輯留給 Phase 004
- Dashboard 前端實作（API 設計好，前端 Phase 003 再做）
- 使用者帳號系統（推播保持無差別，不做個人化篩選）
- LLM extraction（暫不支援 Threads / Workday）
