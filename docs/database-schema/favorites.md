# `favorites`

## Purpose

Single-user bookmarks for jobs. A favorite identifies a job by its source and source-specific job ID; it does not reference `jobs` with a foreign key.

## Columns

| Column | Type | Nullable | Default | Description |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('favorites_id_seq')` | Surrogate primary key for the favorite record. |
| `source` | `VARCHAR(32)` | No | - | Job platform identifier, for example `yourator`, `cakeresume`, or `104`. |
| `source_job_id` | `VARCHAR(255)` | No | - | Job identifier assigned by the source platform. Its interpretation follows `source`. |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | Time when the favorite was created. |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `favorites_pkey` | Primary key | `PRIMARY KEY (id)` |
| `favorites_source_source_job_id_key` | Unique | `UNIQUE (source, source_job_id)` |

## Indexes

| Name | Definition | Purpose |
|---|---|---|
| `favorites_pkey` | Unique B-tree on `id` | Primary-key lookup. |
| `favorites_source_source_job_id_key` | Unique B-tree on `(source, source_job_id)` | Prevents the same source job from being favorited more than once. |

## Relationships

There is no database foreign key to `jobs`; application code resolves the favorite against `(source, source_job_id)`.

