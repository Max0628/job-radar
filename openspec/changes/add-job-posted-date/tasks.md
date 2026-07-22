# Tasks: add-job-posted-date

## 1. DB Schema

- [ ] 1.1 新增 `worker/src/main/resources/db/migration/V6__add_job_posted_at.sql`：
      `ALTER TABLE jobs ADD COLUMN posted_at TIMESTAMPTZ NULL;`（確認 collector/api 是否共用同一份
      migration 目錄或各自獨立，照現有專案結構為準）

## 2. worker：資料模型與解析

- [ ] 2.1 `NormalizedJob` 新增 `postedAt`（`Instant`）為主建構子最後一個欄位，新增對應現有
      14 欄位呼叫端的 backward-compat 建構子（比照現有兩層 pattern）
- [ ] 2.2 `YouratorRawPayloadParser`：解析 `datePosted`（格式 `"yyyy-MM-dd HH:mm:ss Z"`），
      失敗時回傳 null 並記錄 warning log，不拋例外
- [ ] 2.3 `CakeResumeRawPayloadParser`：解析 `content_updated_at`（標準 ISO-8601，`Instant.parse`），
      失敗時回傳 null 並記錄 warning log
- [ ] 2.4 `JobRepository`（worker）：`upsert` 的 `INSERT` 與 `ON CONFLICT DO UPDATE` 都加上
      `posted_at`

## 3. common / api

- [ ] 3.1 `common.domain.Job` record 新增 `postedAt` 欄位
- [ ] 3.2 `api` 的 `JobRepository`：`SORTABLE_COLUMNS` 新增 `"postedAt", "posted_at"`；查詢
      SQL 的 SELECT 欄位清單加上 `posted_at`；預設排序（未帶 `_sort` 時）改成
      `posted_at DESC NULLS LAST`；`_sort=postedAt` 時同樣要接上 `NULLS LAST`（不能只有預設
      排序處理，顯式指定排序也要處理，否則前端切換排序時又會退回 `NULLS FIRST` 的錯誤行為）
- [ ] 3.3 `JobResponse` DTO 新增 `postedAt`，`from(Job, Long)` 帶上這個欄位

## 4. frontend

- [ ] 4.1 `frontend/src/types/index.ts` 的 `Job` type 新增 `postedAt`
- [ ] 4.2 `JobList.tsx` 預設 `sort` 改成 `{ field: "postedAt", order: "DESC" }`
- [ ] 4.3 `JobCard.tsx`（或適當位置）視需要顯示 `postedAt`，跟現有「最後更新：」的
      `lastSeenAt` 顯示區隔清楚（避免使用者混淆兩個日期的意義）

## 5. 測試

- [ ] 5.1 `YouratorRawPayloadParser` 單元測試：正常格式解析、格式異常回傳 null 兩種情境
- [ ] 5.2 `CakeResumeRawPayloadParser` 單元測試：同上
- [ ] 5.3 worker 既有的 idempotency/repository 測試（`NormalizerRepositoriesIdempotencyTest` 等
      `@Tag("requires-docker")` 測試）視情況補上 `posted_at` 相關斷言
- [ ] 5.4 `api` 的 `JobRepository` 排序邏輯測試：至少要覆蓋「`posted_at` 有 null 混雜時，
      DESC 排序 null 值排在最後」這個情境（design.md 標記的高風險細節）

## 6.（選用）既有資料回填

- [ ] 6.1 寫一次性 SQL，從 `raw_documents.payload` 反查每筆既有 CakeResume/Yourator 職缺的
      真實日期，回填 `jobs.posted_at`（比照這次 session 修 CakeResume URL 用的手法：
      `DISTINCT ON (source_job_id) ... ORDER BY fetched_at DESC` 取最新一筆 raw payload）
      —— 這步不阻塞上線，上線後隨時可以補做

## 7. 部署

- [ ] 7.1 三個服務（worker/api/frontend）都改完、測試過後一起 commit + push，讓 CI 走
      test → build → package → deploy
- [ ] 7.2 部署後用真實資料驗證：`GET /api/jobs?_sort=postedAt&_order=DESC` 回應是否符合
      spec 的排序與 null 處理行為
