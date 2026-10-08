# `jobs`

## 用途

每筆已發現職缺的目前 normalized 狀態。一列代表一個來源職缺，唯一識別為 `(source, source_job_id)`。Worker 使用 idempotent upsert 寫入，API 提供 dashboard 讀取。

## 欄位

| Column | Type | Nullable | Default | 說明 |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('jobs_id_seq')` | internal surrogate primary key。 |
| `source` | `VARCHAR(32)` | No | - | 職缺來源平台。 |
| `source_job_id` | `VARCHAR(255)` | No | - | 來源平台提供的穩定職缺識別碼。 |
| `title` | `TEXT` | No | - | normalized 後的職缺標題。 |
| `company` | `TEXT` | Yes | - | normalized 後的公司名稱。 |
| `salary_min` | `BIGINT` | Yes | - | 來源有提供時的最低薪資。 |
| `salary_max` | `BIGINT` | Yes | - | 來源有提供時的最高薪資。 |
| `salary_currency` | `VARCHAR(8)` | Yes | - | 薪資欄位使用的 currency code 或來源值。 |
| `url` | `TEXT` | No | - | 職缺 detail page 或來源 URL。 |
| `content_hash` | `VARCHAR(64)` | No | - | 用來偵測職缺內容是否變更的 hash。 |
| `status` | `VARCHAR(16)` | No | `'NEW'` | lifecycle 狀態：`NEW`、`ACTIVE`、`CLOSED`。API 預設排除 `CLOSED`。 |
| `attrs` | `JSONB` | No | `'{}'::jsonb` | 平台專屬、沒有獨立 common column 的 normalized attributes；結構依 `source` 而定。 |
| `first_seen_at` | `TIMESTAMPTZ` | No | - | 第一次將此來源職缺寫入 database 的時間。 |
| `last_seen_at` | `TIMESTAMPTZ` | No | - | collector/worker 最近一次看到此來源職缺的時間。 |
| `employment_type` | `VARCHAR(32)` | Yes | - | 已知來源 mapping 下的 employment type。 |
| `seniority_level` | `VARCHAR(32)` | Yes | - | 可取得時的 seniority level。 |
| `job_type` | `VARCHAR(32)` | Yes | - | 來源 job type 或 normalized job type。 |
| `lang_name` | `VARCHAR(32)` | Yes | - | 可取得的語言需求／名稱。 |
| `min_work_exp_year` | `INTEGER` | Yes | - | 從來源資料解析出的最低工作年資。 |
| `number_of_openings` | `INTEGER` | Yes | - | 從來源資料解析出的職缺名額。 |
| `city` | `VARCHAR(32)` | Yes | - | normalized 城市／縣市名稱。 |
| `district` | `VARCHAR(32)` | Yes | - | normalized 區／鄉鎮市名稱。 |
| `posted_at` | `TIMESTAMPTZ` | Yes | - | 來源提供的刊登或更新時間。 |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `jobs_pkey` | Primary key | `PRIMARY KEY (id)` |
| `jobs_source_source_job_id_key` | Unique | `UNIQUE (source, source_job_id)` |

## Indexes

| Name | Definition | 用途 |
|---|---|---|
| `jobs_pkey` | Unique B-tree on `id` | Primary key 查詢。 |
| `jobs_source_source_job_id_key` | Unique B-tree on `(source, source_job_id)` | idempotent upsert 與來源職缺查詢。 |
| `idx_jobs_last_seen_at` | B-tree on `(last_seen_at)` | 最近看見時間與 closed-sweep 相關查詢。 |
| `idx_jobs_status` | B-tree on `(status)` | 依狀態篩選。 |
| `idx_jobs_source_status` | B-tree on `(source, status)` | 依來源與狀態篩選。 |
| `idx_jobs_status_last_seen` | B-tree on `(status, last_seen_at DESC)` | 依狀態查詢並按照最近看見時間排序。 |
| `idx_jobs_source_first_seen_at` | B-tree on `(source, first_seen_at)` | 依來源與時間區間統計。 |
| `idx_jobs_city_district` | B-tree on `(city, district)` | 地區篩選。 |
| `idx_jobs_title_gin` | GIN on `to_tsvector('english'::regconfig, title)` | title 的 full-text search。 |

## Relationships

沒有從 `jobs` 指向歷史資料表的 database foreign key。`job_snapshots`、`raw_documents`、`favorites` 都透過 `(source, source_job_id)` 形成邏輯關聯。

