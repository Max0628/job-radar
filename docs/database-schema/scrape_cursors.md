# `scrape_cursors`

## 用途

保存每個 `search_queries` 的排程狀態與 deep scan 進度。Scheduler 使用 `last_scanned_at` 判斷 query 是否到期；deep scan 使用 page 與 completion 欄位接續掃描。

## 欄位

| Column | Type | Nullable | Default | 說明 |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('scrape_cursors_id_seq')` | cursor 的 internal surrogate primary key。 |
| `search_query_id` | `BIGINT` | No | - | 這筆 cursor 所屬的搜尋設定。 |
| `last_scanned_at` | `TIMESTAMPTZ` | Yes | - | 最近一次完成掃描嘗試的時間，Scheduler 用它計算下次執行時間。 |
| `last_page_scanned` | `INTEGER` | Yes | - | 未完成 deep scan 時，下次要接續的 page；deep scan 完成後會清空。 |
| `last_deep_scan_completed_at` | `TIMESTAMPTZ` | Yes | - | 最近一次完整 deep scan 完成的時間，用來判斷下次 deep scan 是否到期。 |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `scrape_cursors_pkey` | Primary key | `PRIMARY KEY (id)` |
| `scrape_cursors_search_query_id_key` | Unique | `UNIQUE (search_query_id)` |
| `scrape_cursors_search_query_id_fkey` | Foreign key | `FOREIGN KEY (search_query_id) REFERENCES search_queries(id)` |

## Indexes

| Name | Definition | 用途 |
|---|---|---|
| `scrape_cursors_pkey` | Unique B-tree on `id` | Primary key 查詢。 |
| `scrape_cursors_search_query_id_key` | Unique B-tree on `(search_query_id)` | 保證一個 query 只有一個 cursor，並加速狀態查詢。 |

