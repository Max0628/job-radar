-- Phase 002: 多來源支持（CakeResume）+ 狀態管理 + Dashboard 預留欄位

-- 1. jobs.status 改用 NEW/ACTIVE/CLOSED 語意，保留 DEFAULT（JobRepository.upsert 會明確寫入，
--    但保留 default 當安全網，避免任何遺漏 status 的 INSERT 因 NOT NULL 失敗）
ALTER TABLE jobs ALTER COLUMN status SET DEFAULT 'NEW';
UPDATE jobs SET status = 'ACTIVE' WHERE status = 'open';

-- 2. 新增欄位支持 Dashboard 查詢與平台差異欄位（皆可 NULL，容納平台沒有該資訊的情況）
ALTER TABLE jobs ADD COLUMN employment_type VARCHAR(32);
ALTER TABLE jobs ADD COLUMN seniority_level VARCHAR(32);
ALTER TABLE jobs ADD COLUMN job_type VARCHAR(32);
ALTER TABLE jobs ADD COLUMN lang_name VARCHAR(32);
ALTER TABLE jobs ADD COLUMN min_work_exp_year INT;
ALTER TABLE jobs ADD COLUMN number_of_openings INT;

-- 3. 新增索引以支持查詢效能
CREATE INDEX idx_jobs_source_status ON jobs (source, status);
CREATE INDEX idx_jobs_status_last_seen ON jobs (status, last_seen_at DESC);
CREATE INDEX idx_jobs_title_gin ON jobs USING GIN(to_tsvector('english'::regconfig, title));

-- 4. scrape_runs 補上「本輪發現幾筆新職缺」與未來 closed sweep 用的「本輪判定下架幾筆」
ALTER TABLE scrape_runs RENAME COLUMN jobs_new TO jobs_discovered;
ALTER TABLE scrape_runs ADD COLUMN jobs_deleted INT;

-- 5. search_queries 加地區欄位（Yourator 的 area[]、CakeResume 的 filters.location 都需要，
--    可 NULL 代表「不限地區」）
ALTER TABLE search_queries ADD COLUMN location VARCHAR(64);

-- 6. CakeResume 種子資料（比照既有 Yourator 種子的節奏，見 V2）
INSERT INTO search_queries (source, keyword, location, max_pages, interval_minutes, enabled)
VALUES ('cakeresume', 'devops', 'Taipei', 10, 120, TRUE);

INSERT INTO scrape_cursors (search_query_id)
SELECT id FROM search_queries WHERE source = 'cakeresume' AND keyword = 'devops';

-- 注釋：
-- - jobs.status 允許值：NEW（新發現）、ACTIVE（已見過）、CLOSED（已下架，Phase 004）
-- - raw_documents / job_snapshots 欄位結構本次不動，維持既有的 fetched_at 命名
--   （改名牽動 RawDocumentRepository 與既有測試，非本次 Phase 002 必要變更）
