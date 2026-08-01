-- 淺掃/深掃合併成一套邏輯（見 architecture.md D6）：last_deep_scan_completed_at 記錄
-- 「上次一次真正翻完全部頁面的深掃」是什麼時候完成的，決定下次該不該跑深掃模式。
-- 跟既有的 last_scanned_at（每次掃描，不管淺或深，都會更新）、last_page_scanned
-- （深掃進行中的接續頁碼，完成時歸零）語意各自獨立，三者不能互相取代。
ALTER TABLE scrape_cursors ADD COLUMN last_deep_scan_completed_at TIMESTAMPTZ;
