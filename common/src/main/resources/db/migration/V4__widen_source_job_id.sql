-- 修復：CakeResume 的 path slug（拿來當 source_job_id，見 Phase 002 端到端驗證發現）
-- 實測長度可達 100 字元（第三方 ATS 整合職缺會有 UUID 前綴，如
-- "eff9c92f-a718-465a-978c-7110dfaf8c43-ai-devops-software-engineer-..."），
-- 原本的 VARCHAR(64) 對 Yourator 的純數字 ID 足夠，但容不下 CakeResume 的 slug。
-- 放寬到 VARCHAR(255)，涵蓋三張都有 source_job_id 的表。

ALTER TABLE jobs ALTER COLUMN source_job_id TYPE VARCHAR(255);
ALTER TABLE job_snapshots ALTER COLUMN source_job_id TYPE VARCHAR(255);
ALTER TABLE raw_documents ALTER COLUMN source_job_id TYPE VARCHAR(255);
