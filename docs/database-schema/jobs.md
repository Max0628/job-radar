# `jobs`

## Purpose

Current normalized state of each discovered job. One row represents one source job, identified by `(source, source_job_id)`. The worker writes this table with an idempotent upsert; the API reads it for the dashboard.

## Columns

| Column | Type | Nullable | Default | Description |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('jobs_id_seq')` | Internal surrogate primary key. |
| `source` | `VARCHAR(32)` | No | - | Source platform that published the job. |
| `source_job_id` | `VARCHAR(255)` | No | - | Stable job identifier from the source platform. |
| `title` | `TEXT` | No | - | Normalized job title. |
| `company` | `TEXT` | Yes | - | Normalized company name. |
| `salary_min` | `BIGINT` | Yes | - | Normalized lower salary bound, when supplied by the source. |
| `salary_max` | `BIGINT` | Yes | - | Normalized upper salary bound, when supplied by the source. |
| `salary_currency` | `VARCHAR(8)` | Yes | - | Currency code or source-provided currency value for salary fields. |
| `url` | `TEXT` | No | - | Detail page or source URL for the job. |
| `content_hash` | `VARCHAR(64)` | No | - | Hash of normalized content used to detect changes. |
| `status` | `VARCHAR(16)` | No | `'NEW'` | Lifecycle state: `NEW`, `ACTIVE`, or `CLOSED`. `CLOSED` is excluded from the default API listing. |
| `attrs` | `JSONB` | No | `'{}'::jsonb` | Source-specific normalized attributes that do not have common columns. Shape depends on `source`. |
| `first_seen_at` | `TIMESTAMPTZ` | No | - | Time this source job was first inserted into the database. |
| `last_seen_at` | `TIMESTAMPTZ` | No | - | Time the collector/worker most recently saw this source job. |
| `employment_type` | `VARCHAR(32)` | Yes | - | Normalized employment type when the source mapping is known. |
| `seniority_level` | `VARCHAR(32)` | Yes | - | Normalized seniority level when available. |
| `job_type` | `VARCHAR(32)` | Yes | - | Source job-type value or normalized job-type value. |
| `lang_name` | `VARCHAR(32)` | Yes | - | Language requirement/name when available. |
| `min_work_exp_year` | `INTEGER` | Yes | - | Minimum years of work experience when parsed from source data. |
| `number_of_openings` | `INTEGER` | Yes | - | Number of openings when parsed from source data. |
| `city` | `VARCHAR(32)` | Yes | - | Normalized city/county name. |
| `district` | `VARCHAR(32)` | Yes | - | Normalized district/township name. |
| `posted_at` | `TIMESTAMPTZ` | Yes | - | Source-provided publication/update time, when available. |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `jobs_pkey` | Primary key | `PRIMARY KEY (id)` |
| `jobs_source_source_job_id_key` | Unique | `UNIQUE (source, source_job_id)` |

## Indexes

| Name | Definition | Purpose |
|---|---|---|
| `jobs_pkey` | Unique B-tree on `id` | Primary-key lookup. |
| `jobs_source_source_job_id_key` | Unique B-tree on `(source, source_job_id)` | Idempotent upsert and source-job lookup. |
| `idx_jobs_last_seen_at` | B-tree on `(last_seen_at)` | Recency/closed-sweep related lookups. |
| `idx_jobs_status` | B-tree on `(status)` | Status filtering. |
| `idx_jobs_source_status` | B-tree on `(source, status)` | Combined source and status filtering. |
| `idx_jobs_status_last_seen` | B-tree on `(status, last_seen_at DESC)` | Status queries ordered by last-seen time. |
| `idx_jobs_source_first_seen_at` | B-tree on `(source, first_seen_at)` | Source/time-window reporting. |
| `idx_jobs_city_district` | B-tree on `(city, district)` | Location filtering. |
| `idx_jobs_title_gin` | GIN on `to_tsvector('english'::regconfig, title)` | Full-text title search. |

## Relationships

There are no database foreign keys from `jobs` to the source-specific history tables. `job_snapshots`, `raw_documents`, and `favorites` are logically related by `(source, source_job_id)`.

