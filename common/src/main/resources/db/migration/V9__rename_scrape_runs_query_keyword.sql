-- add-crawl-improvements: search_queries.keyword 沒了（見 V8），scrape_runs 裡原本
-- 記錄「這輪掃描搜什麼關鍵字」的欄位跟著改記錄分類清單，改名避免欄位名稱誤導
-- （欄位還在，只是意義換成 categories 的逗號分隔字串，方便直接看這個表時知道
-- 這輪掃描的範圍是什麼，不用另外 join search_queries）。

ALTER TABLE scrape_runs RENAME COLUMN query_keyword TO query_categories;
