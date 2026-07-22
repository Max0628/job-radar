# Tasks: add-job-posted-date

## 1. DB Schema

- [x] 1.1 新增 `common/src/main/resources/db/migration/V6__add_job_posted_at.sql`（migration
      實際共用放在 `common` module，不是 tasks.md 原先寫的 `worker`，三個 boot jar 都會抓到）：
      `ALTER TABLE jobs ADD COLUMN posted_at TIMESTAMPTZ NULL;`

## 2. worker：資料模型與解析

- [x] 2.1 `NormalizedJob` 新增 `postedAt`（`Instant`）為主建構子最後一個欄位，新增對應現有
      14 欄位呼叫端的 backward-compat 建構子（比照現有兩層 pattern）
- [x] 2.2 `YouratorRawPayloadParser`：解析 `datePosted`（格式 `"yyyy-MM-dd HH:mm:ss Z"`），
      失敗時回傳 null 並記錄 warning log，不拋例外
- [x] 2.3 `CakeResumeRawPayloadParser`：解析 `content_updated_at`（標準 ISO-8601，`Instant.parse`），
      失敗時回傳 null 並記錄 warning log
- [x] 2.4 `JobRepository`（worker）：`upsert` 的 `INSERT` 與 `ON CONFLICT DO UPDATE` 都加上
      `posted_at`

## 3. common / api

- [x] 3.1 `common.domain.Job` record 新增 `postedAt` 欄位
- [x] 3.2 `api` 的 `JobRepository`：`SORTABLE_COLUMNS` 新增 `"postedAt", "posted_at"`；查詢
      SQL 的 SELECT 欄位清單加上 `posted_at`；預設排序（未帶 `_sort` 時）改成
      `posted_at DESC NULLS LAST`；`_sort=postedAt` 時同樣要接上 `NULLS LAST`（不能只有預設
      排序處理，顯式指定排序也要處理，否則前端切換排序時又會退回 `NULLS FIRST` 的錯誤行為）
- [x] 3.3 `JobResponse` DTO 新增 `postedAt`，`from(Job, Long)` 帶上這個欄位

## 4. frontend

- [x] 4.1 `frontend/src/types/index.ts` 的 `Job` type 新增 `postedAt`
- [x] 4.2 `JobList.tsx` 預設 `sort` 改成 `{ field: "postedAt", order: "DESC" }`
- [x] 4.3 `JobCard.tsx`（或適當位置）視需要顯示 `postedAt`，跟現有「最後更新：」的
      `lastSeenAt` 顯示區隔清楚（避免使用者混淆兩個日期的意義）

## 5. 測試

- [x] 5.1 `YouratorRawPayloadParser` 單元測試：正常格式解析、格式異常回傳 null 兩種情境
- [x] 5.2 `CakeResumeRawPayloadParser` 單元測試：同上
- [x] 5.3 worker 既有的 idempotency/repository 測試（`NormalizerRepositoriesIdempotencyTest` 等
      `@Tag("requires-docker")` 測試）確認仍全數通過——冪等邏輯本身不受影響，未額外加
      `posted_at` 斷言（不在既有測試的驗證範圍內）
- [x] 5.4 `api` 的 `JobRepository` 排序邏輯測試：覆蓋「`posted_at` 有 null 混雜時 DESC
      排序 null 值排在最後」，另外也發現並修正一個實作缺口——`JobController` 的 `_sort`
      `defaultValue` 原本硬寫死 `"lastSeenAt"`，導致 `JobRepository` 內的 `getOrDefault`
      fallback 邏輯永遠不會被觸發，一併修正成 `"postedAt"`

## 6.（選用）既有資料回填

- [x] 6.1 對本機開發用 postgres 跑過一次性回填 SQL：CakeResume 366/366 全數回填成功，
      Yourator 211/373 成功（其餘 162 筆 raw_documents payload 本身沒有可解析的
      `datePosted`，符合設計的優雅降級行為，不是回填腳本的問題）。k8s 叢集那份還是空的
      DB，之後真的部署後如果需要對 production 資料回填，同一份 SQL 可以直接重跑

## 7. 部署

- [x] 7.1 三個服務（worker/api/frontend）都改完、測試過後一起 commit（`0bc8121`）+ push，
      讓 CI 走 test → build → package → deploy
- [x] 7.2 本機用真實回填後的資料驗證過：`GET /api/jobs?_start=0&_end=5` 回應確實依
      `postedAt` 由新到舊排序（見上方本機驗證結果）。k8s 部署後還要再驗證一次，因為
      那邊資料量少、大部分職缺目前 posted_at 還是 null，行為會偏向「新掃到的排前面、
      其餘擠在最後」——**已於部署完成後補做**：CI 部署成功、五個 Pod 都換成新版
      image 且健康後，對 k8s 內的 postgres 也跑了同一份回填 SQL（391/391 全數成功），
      透過 port-forward 打 `/api/jobs` 確認排序正確依 `postedAt` 由新到舊排列
