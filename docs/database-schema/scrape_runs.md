# `scrape_runs`

## Purpose

Audit record for each collector scan. It records the source, category scope, timing, page/job counts, result, error, and scan-summary reporting state.

## Columns

| Column | Type | Nullable | Default | Description |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('scrape_runs_id_seq')` | Internal run identifier. |
| `source` | `VARCHAR(32)` | No | - | Source platform scanned. |
| `query_categories` | `TEXT` | No | - | Comma-separated category/profession values used for this run; retained for audit display. |
| `started_at` | `TIMESTAMPTZ` | No | - | Time the scan started. |
| `finished_at` | `TIMESTAMPTZ` | Yes | - | Time the scan completed or failed. |
| `pages_scanned` | `INTEGER` | No | `0` | Number of pages accepted/scanned during the run. |
| `jobs_seen` | `INTEGER` | No | `0` | Number of jobs observed in list responses. |
| `jobs_discovered` | `INTEGER` | Yes | - | Number of jobs published/discovered by the scan; this column was formerly named `jobs_new`. |
| `status` | `VARCHAR(16)` | No | - | Run result, currently values such as `running`, `success`, or `failed`. |
| `error_message` | `TEXT` | Yes | - | Error message when the run fails. |
| `jobs_deleted` | `INTEGER` | Yes | - | Reserved/result count for jobs marked deleted by a future or extended closed-sweep process. |
| `scan_mode` | `VARCHAR(8)` | Yes | - | Scan mode, expected to be `light` or `deep`. |
| `terminated_early` | `BOOLEAN` | Yes | - | Whether the scan stopped before reaching the platform's end. |
| `report_sent_at` | `TIMESTAMPTZ` | Yes | - | Time the scan summary report was sent; used to avoid duplicate reports. |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `scrape_runs_pkey` | Primary key | `PRIMARY KEY (id)` |

## Indexes

| Name | Definition | Purpose |
|---|---|---|
| `scrape_runs_pkey` | Unique B-tree on `id` | Primary-key lookup. |
| `idx_scrape_runs_source_started_at` | B-tree on `(source, started_at DESC)` | Recent run history and source-based monitoring. |

