-- add-crawl-improvements: 拿掉分頁上限，scraper 改成單純依 hasMore/total_entries
-- 判斷是否翻到底（見 design.md）。這個欄位拿掉後不會被任何程式碼讀取，
-- 留著只會誤導使用者以為調整它還有作用，直接砍掉比留一個沒用的欄位乾淨。

ALTER TABLE search_queries DROP COLUMN max_pages;
