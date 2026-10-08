# `raw_documents`

## Purpose

Stores the complete raw detail payload fetched from a source platform. It is retained so parsers can be replayed or corrected without fetching the source again.

## Columns

| Column | Type | Nullable | Default | Description |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('raw_documents_id_seq')` | Internal surrogate primary key. |
| `source` | `VARCHAR(32)` | No | - | Source platform. |
| `source_job_id` | `VARCHAR(255)` | No | - | Source platform's job identifier. |
| `fetched_at` | `TIMESTAMPTZ` | No | - | Time the raw detail payload was fetched. |
| `payload` | `JSONB` | No | - | Complete source response payload. Structure is platform-specific. |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `raw_documents_pkey` | Primary key | `PRIMARY KEY (id)` |
| `raw_documents_source_source_job_id_fetched_at_key` | Unique | `UNIQUE (source, source_job_id, fetched_at)` |

## Indexes

| Name | Definition | Purpose |
|---|---|---|
| `raw_documents_pkey` | Unique B-tree on `id` | Primary-key lookup. |
| `raw_documents_source_source_job_id_fetched_at_key` | Unique B-tree on `(source, source_job_id, fetched_at)` | Prevents duplicate raw payloads for the same source job and fetch time. |

## Relationships

The table has no foreign key to `jobs`; the logical key is `(source, source_job_id)`.

