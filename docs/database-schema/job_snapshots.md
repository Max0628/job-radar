# `job_snapshots`

## Purpose

Append-only history of normalized job values observed during scraping. A snapshot is associated with a source job and a scrape timestamp; repeated content is normally avoided by the worker's content-hash check.

## Columns

| Column | Type | Nullable | Default | Description |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('job_snapshots_id_seq')` | Internal surrogate primary key. |
| `source` | `VARCHAR(32)` | No | - | Source platform. |
| `source_job_id` | `VARCHAR(255)` | No | - | Source platform's job identifier. |
| `scraped_at` | `TIMESTAMPTZ` | No | - | Time the normalized snapshot was recorded. |
| `title` | `TEXT` | Yes | - | Job title at this observation. |
| `company` | `TEXT` | Yes | - | Company name at this observation. |
| `salary_min` | `BIGINT` | Yes | - | Lower salary bound at this observation. |
| `salary_max` | `BIGINT` | Yes | - | Upper salary bound at this observation. |
| `content_hash` | `VARCHAR(64)` | Yes | - | Content hash used to identify the observed version. |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `job_snapshots_pkey` | Primary key | `PRIMARY KEY (id)` |
| `job_snapshots_source_source_job_id_scraped_at_key` | Unique | `UNIQUE (source, source_job_id, scraped_at)` |

## Indexes

| Name | Definition | Purpose |
|---|---|---|
| `job_snapshots_pkey` | Unique B-tree on `id` | Primary-key lookup. |
| `job_snapshots_source_source_job_id_scraped_at_key` | Unique B-tree on `(source, source_job_id, scraped_at)` | Prevents duplicate snapshots for the same source job and scrape time. |

## Relationships

The table has no foreign key to `jobs`; the logical key is `(source, source_job_id)`.

