-- 掃描摘要回報 Discord 用（見 architecture.md D21）。scan_mode/terminated_early 記錄
-- 這輪掃描的模式資訊，report_sent_at 記錄回報狀態，避免同一輪掃描被重複回報。
ALTER TABLE scrape_runs ADD COLUMN scan_mode VARCHAR(8);
ALTER TABLE scrape_runs ADD COLUMN terminated_early BOOLEAN;
ALTER TABLE scrape_runs ADD COLUMN report_sent_at TIMESTAMPTZ;

-- 既有索引沒有覆蓋「依來源查 first_seen_at 落在某時間窗內」這個查詢樣式（回報新增筆數
-- 用），新增這個索引。
CREATE INDEX idx_jobs_source_first_seen_at ON jobs (source, first_seen_at);
