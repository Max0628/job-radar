# PostgreSQL Schema Reference

This directory documents the current PostgreSQL schema table by table.

## Source of truth

The structural inventory was checked against the live `job-radar` PostgreSQL instance in Kubernetes on **2026-10-09**:

- tables and columns from `information_schema.columns`
- indexes from `pg_catalog.pg_indexes`
- constraints from `pg_constraint`
- existing comments from `pg_description`

The live database currently has **no table or column comments**. The descriptions in these Markdown files are therefore a maintained project reference, derived from the live schema, Flyway migrations, Java domain models, repositories, and architecture documentation.

## Tables

| Table | Role |
|---|---|
| [`jobs`](jobs.md) | Current normalized job state. |
| [`job_snapshots`](job_snapshots.md) | Append-only normalized job history. |
| [`raw_documents`](raw_documents.md) | Source detail payloads for replay/debugging. |
| [`search_queries`](search_queries.md) | Crawler search configuration. |
| [`scrape_cursors`](scrape_cursors.md) | Per-query scheduling and deep-scan progress. |
| [`scrape_runs`](scrape_runs.md) | Crawler run audit records. |
| [`favorites`](favorites.md) | Single-user job bookmarks. |
| [`flyway_schema_history`](flyway_schema_history.md) | Flyway migration bookkeeping. |

## Maintenance rule

When a migration changes a table, update the corresponding Markdown file in the same change. If a field's business meaning is uncertain, record the uncertainty explicitly instead of guessing from the column name alone.

