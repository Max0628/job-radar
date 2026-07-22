-- add-job-posted-date: 職缺列表排序改用平台真實日期（datePosted / content_updated_at），
-- 取代目前語意錯誤的 last_seen_at（見 design.md）

ALTER TABLE jobs ADD COLUMN posted_at TIMESTAMPTZ NULL;
