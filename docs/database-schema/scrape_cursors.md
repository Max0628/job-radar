# `scrape_cursors`

## Purpose

Stores scheduling and deep-scan progress for each row in `search_queries`. The scheduler uses `last_scanned_at` to decide whether a query is due; deep scans use the page and completion fields for continuation.

## Columns

| Column | Type | Nullable | Default | Description |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('scrape_cursors_id_seq')` | Internal surrogate primary key. |
| `search_query_id` | `BIGINT` | No | - | The search query whose scan state is stored. |
| `last_scanned_at` | `TIMESTAMPTZ` | Yes | - | Time of the most recent completed scan attempt used by scheduling. |
| `last_page_scanned` | `INTEGER` | Yes | - | Page to resume for an unfinished deep scan; cleared when a deep scan reaches the end. |
| `last_deep_scan_completed_at` | `TIMESTAMPTZ` | Yes | - | Time the most recent full deep scan completed. Used to decide when the next deep scan is due. |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `scrape_cursors_pkey` | Primary key | `PRIMARY KEY (id)` |
| `scrape_cursors_search_query_id_key` | Unique | `UNIQUE (search_query_id)` |
| `scrape_cursors_search_query_id_fkey` | Foreign key | `FOREIGN KEY (search_query_id) REFERENCES search_queries(id)` |

## Indexes

| Name | Definition | Purpose |
|---|---|---|
| `scrape_cursors_pkey` | Unique B-tree on `id` | Primary-key lookup. |
| `scrape_cursors_search_query_id_key` | Unique B-tree on `(search_query_id)` | One cursor per search query and fast query-state lookup. |

