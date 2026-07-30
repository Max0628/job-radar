-- add-crawl-improvements: 拿掉自由輸入的 keyword，兩個平台的分類/professions 多選
-- 皆已實測證實是真正的聯集（Yourator category[]、CakeResume professions 皆驗證過），
-- CakeResume 的 query 欄位反而不支援聯集語意（多個詞一起帶結果變少，比較像 AND/
-- 片語比對），用分類統一取代更一致，也不用再處理兩個平台語意不同的問題（見
-- design.md 的實測記錄）。
--
-- 這個欄位上的 UNIQUE (source, keyword) 約束（search_queries_source_keyword_key）
-- 會隨著欄位一起被 Postgres 自動砍掉，不用額外下 DROP CONSTRAINT。這個約束原本就是
-- 隱患：keyword 常是空字串時，同一個 source 只能有一筆設定（撞唯一約束），現在
-- 拿掉 keyword 之後如果不順便拿掉這個約束，會變成「每個 source 永遠只能有一筆
-- 設定」，完全卡死使用情境。這張表是個人使用的設定表，不需要資料庫層級的唯一性
-- 保證去防手動建出重複設定——頂多多掃一次，不是資料正確性問題。

ALTER TABLE search_queries DROP COLUMN keyword;
