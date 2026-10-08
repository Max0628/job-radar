# `job_snapshots`

## 用途

記錄每次爬蟲觀察到的 normalized job 歷史資料。這張表是 append-only；同一個 source job 在不同 `scraped_at` 可以有多筆快照。

## 欄位

| Column | Type | Nullable | Default | 說明 |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('job_snapshots_id_seq')` | 快照紀錄的 internal surrogate primary key。 |
| `source` | `VARCHAR(32)` | No | - | 職缺來源平台。 |
| `source_job_id` | `VARCHAR(255)` | No | - | 來源平台的職缺識別碼。 |
| `scraped_at` | `TIMESTAMPTZ` | No | - | 建立這筆 normalized snapshot 的時間。 |
| `title` | `TEXT` | Yes | - | 當次觀察到的職缺標題。 |
| `company` | `TEXT` | Yes | - | 當次觀察到的公司名稱。 |
| `salary_min` | `BIGINT` | Yes | - | 當次觀察到的最低薪資。 |
| `salary_max` | `BIGINT` | Yes | - | 當次觀察到的最高薪資。 |
| `content_hash` | `VARCHAR(64)` | Yes | - | 用來辨識內容版本的 hash。 |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `job_snapshots_pkey` | Primary key | `PRIMARY KEY (id)` |
| `job_snapshots_source_source_job_id_scraped_at_key` | Unique | `UNIQUE (source, source_job_id, scraped_at)` |

## Indexes

| Name | Definition | 用途 |
|---|---|---|
| `job_snapshots_pkey` | Unique B-tree on `id` | Primary key 查詢。 |
| `job_snapshots_source_source_job_id_scraped_at_key` | Unique B-tree on `(source, source_job_id, scraped_at)` | 防止同一來源職缺在同一時間重複寫入快照。 |

## Relationships

沒有指向 `jobs` 的 foreign key；邏輯關聯鍵是 `(source, source_job_id)`。

