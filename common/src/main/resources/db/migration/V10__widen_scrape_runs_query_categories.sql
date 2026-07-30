-- 修正 V9 的疏漏：改名時只換了欄位名稱，沒有跟著放寬長度限制。這個欄位原本是
-- query_keyword VARCHAR(128)，設計時假設內容是「一個關鍵字」；改存分類清單的
-- 逗號接合字串後，CakeResume 的 professions 代碼是長字串（如
-- "it_system-network-administrator"），6 個接起來輕鬆超過 128 字元，導致
-- INSERT INTO scrape_runs 直接報錯（value too long for type character
-- varying(128)），CakeResume 的掃描從部署後每一輪 tick 都在這一步失敗，
-- 連平台 API 都還沒打到（實際發生：部署後六個多小時、五十幾次 tick 全部失敗）。
--
-- 改成 TEXT（不設長度上限）——這個欄位純粹是給人看的稽核用途，不需要任何長度
-- 限制去保護什麼，之後不管分類清單多長都不會再踩同一個坑。

ALTER TABLE scrape_runs ALTER COLUMN query_categories TYPE TEXT;
