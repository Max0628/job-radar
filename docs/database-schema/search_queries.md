# `search_queries`

## Purpose

User-managed crawler configuration. Each row describes one source, location, category scope, scan interval, and enabled/disabled state. The scheduler reads enabled rows and uses the related `scrape_cursors` row for timing and deep-scan progress.

## Columns

| Column | Type | Nullable | Default | Description |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('search_queries_id_seq')` | Internal search configuration identifier. |
| `source` | `VARCHAR(32)` | No | - | Source platform, such as `yourator`, `cakeresume`, or `104`. |
| `interval_minutes` | `INTEGER` | No | `120` | Minimum interval between scans for this configuration. |
| `enabled` | `BOOLEAN` | No | `TRUE` | Whether the scheduler may run this configuration. |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | Time the configuration was created. |
| `location` | `VARCHAR(64)` | Yes | - | Source-specific location filter. Yourator uses an area code; other sources use their own accepted location value. |
| `categories` | `JSONB` | Yes | - | Source-specific category/profession values. Yourator stores category names; CakeResume stores profession codes; 104 stores category codes. |
| `disabled_reason` | `TEXT` | Yes | - | Reason for an automatic source/query disable, such as a blocked-source HTTP response. Cleared when the query is re-enabled through the API. |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `search_queries_pkey` | Primary key | `PRIMARY KEY (id)` |

## Indexes

| Name | Definition | Purpose |
|---|---|---|
| `search_queries_pkey` | Unique B-tree on `id` | Primary-key lookup. |

## Relationships

`scrape_cursors.search_query_id` references `search_queries.id`. The current database has no uniqueness constraint on `(source, location, categories)`; duplicate configurations are possible at the database level.

