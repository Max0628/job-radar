# `search_queries`

## 用途

使用者管理的 crawler 設定。每列描述一個來源、地區、分類範圍、掃描間隔與啟用狀態。Scheduler 讀取 enabled 的列，並使用對應的 `scrape_cursors` 管理時間與 deep scan 進度。

## 欄位

| Column | Type | Nullable | Default | 說明 |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('search_queries_id_seq')` | 搜尋設定的 internal ID。 |
| `source` | `VARCHAR(32)` | No | - | 職缺來源平台，例如 `yourator`、`cakeresume`、`104`。 |
| `interval_minutes` | `INTEGER` | No | `120` | 這筆設定兩次掃描之間的最小間隔。 |
| `enabled` | `BOOLEAN` | No | `TRUE` | Scheduler 是否可以執行這筆設定。 |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | 建立設定的時間。 |
| `location` | `VARCHAR(64)` | Yes | - | 來源平台專用的地區篩選值。Yourator 使用 area code，其他平台使用各自接受的格式。 |
| `categories` | `JSONB` | Yes | - | 來源平台專用的分類／profession 值。Yourator 存分類名稱，CakeResume 存 profession code，104 存 category code。 |
| `disabled_reason` | `TEXT` | Yes | - | 自動停用原因，例如來源回傳 blocked HTTP response。透過 API 重新啟用時會清除。 |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `search_queries_pkey` | Primary key | `PRIMARY KEY (id)` |

## Indexes

| Name | Definition | 用途 |
|---|---|---|
| `search_queries_pkey` | Unique B-tree on `id` | Primary key 查詢。 |

## Relationships

`scrape_cursors.search_query_id` 參照 `search_queries.id`。目前 database 沒有 `(source, location, categories)` 的 unique constraint，因此 database 層允許建立重複設定。

