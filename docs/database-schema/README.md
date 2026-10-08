# PostgreSQL Schema 參考

本資料夾逐張記錄目前 PostgreSQL schema。

## 資料來源

結構盤點於 **2026-10-09** 直接對照 K8s 中 `job-radar` PostgreSQL：

- table / column：`information_schema.columns`
- index：`pg_catalog.pg_indexes`
- constraint：`pg_constraint`
- database comment：`pg_description`

目前 live database 沒有任何 table 或 column comment。這些 Markdown 是依據實際 schema、Flyway migrations、Java domain model、repository 與 architecture 文件整理的專案參考資料。

## Tables

| Table | 用途 |
|---|---|
| [`jobs`](jobs.md) | 目前的 normalized job 狀態。 |
| [`job_snapshots`](job_snapshots.md) | 職缺的 append-only 歷史快照。 |
| [`raw_documents`](raw_documents.md) | 平台原始 detail payload。 |
| [`search_queries`](search_queries.md) | crawler 搜尋設定。 |
| [`scrape_cursors`](scrape_cursors.md) | 每個 query 的排程與 deep scan 進度。 |
| [`scrape_runs`](scrape_runs.md) | 每次 crawler 執行的 audit 紀錄。 |
| [`favorites`](favorites.md) | 單一使用者的職缺收藏。 |
| [`flyway_schema_history`](flyway_schema_history.md) | Flyway migration 紀錄。 |

## 維護規則

每次 migration 修改 table 時，同一個 change 必須同步更新對應的 Markdown。若欄位的 business meaning 尚未確認，應明確標記不確定，不要只依欄位名稱猜測。

