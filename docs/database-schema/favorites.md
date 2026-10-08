# `favorites`

## 用途

單一使用者的職缺收藏表。收藏透過 `source` 與來源平台的職缺 ID 識別職缺，沒有用 foreign key 直接參照 `jobs`。

## 欄位

| Column | Type | Nullable | Default | 說明 |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('favorites_id_seq')` | 收藏紀錄的 internal surrogate primary key。 |
| `source` | `VARCHAR(32)` | No | - | 職缺來源平台，例如 `yourator`、`cakeresume`、`104`。 |
| `source_job_id` | `VARCHAR(255)` | No | - | 來源平台提供的職缺識別碼，實際格式依 `source` 而定。 |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | 建立收藏的時間。 |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `favorites_pkey` | Primary key | `PRIMARY KEY (id)` |
| `favorites_source_source_job_id_key` | Unique | `UNIQUE (source, source_job_id)` |

## Indexes

| Name | Definition | 用途 |
|---|---|---|
| `favorites_pkey` | Unique B-tree on `id` | Primary key 查詢。 |
| `favorites_source_source_job_id_key` | Unique B-tree on `(source, source_job_id)` | 防止同一個來源職缺被重複收藏。 |

## Relationships

沒有指向 `jobs` 的 database foreign key；application code 透過 `(source, source_job_id)` 找回職缺。

