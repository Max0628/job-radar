# `raw_documents`

## 用途

保存從來源平台抓回的完整 detail payload，讓之後可以重播 parser、修正 normalization，或進行除錯，而不必重新請求來源平台。

## 欄位

| Column | Type | Nullable | Default | 說明 |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('raw_documents_id_seq')` | raw document 的 internal surrogate primary key。 |
| `source` | `VARCHAR(32)` | No | - | 職缺來源平台。 |
| `source_job_id` | `VARCHAR(255)` | No | - | 來源平台的職缺識別碼。 |
| `fetched_at` | `TIMESTAMPTZ` | No | - | 抓取 detail payload 的時間。 |
| `payload` | `JSONB` | No | - | 完整的來源平台 response；結構依平台而不同。 |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `raw_documents_pkey` | Primary key | `PRIMARY KEY (id)` |
| `raw_documents_source_source_job_id_fetched_at_key` | Unique | `UNIQUE (source, source_job_id, fetched_at)` |

## Indexes

| Name | Definition | 用途 |
|---|---|---|
| `raw_documents_pkey` | Unique B-tree on `id` | Primary key 查詢。 |
| `raw_documents_source_source_job_id_fetched_at_key` | Unique B-tree on `(source, source_job_id, fetched_at)` | 防止同一來源職缺在同一抓取時間重複保存 payload。 |

## Relationships

沒有指向 `jobs` 的 foreign key；邏輯關聯鍵是 `(source, source_job_id)`。

