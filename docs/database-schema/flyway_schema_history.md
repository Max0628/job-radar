# `flyway_schema_history`

## Purpose

Flyway's migration bookkeeping table. It is managed by Flyway and is not application business data.

## Columns

| Column | Type | Nullable | Default | Description |
|---|---|---:|---|---|
| `installed_rank` | `INTEGER` | No | - | Ordering number assigned to an installed migration. |
| `version` | `VARCHAR(50)` | Yes | - | Flyway migration version; repeatable migrations may not have a version. |
| `description` | `VARCHAR(200)` | No | - | Human-readable migration description. |
| `type` | `VARCHAR(20)` | No | - | Flyway migration type. |
| `script` | `VARCHAR(1000)` | No | - | Migration script name/path recorded by Flyway. |
| `checksum` | `INTEGER` | Yes | - | Checksum used by Flyway to detect changed migration files. |
| `installed_by` | `VARCHAR(100)` | No | - | Database user that installed the migration. |
| `installed_on` | `TIMESTAMP` | No | `now()` | Time the migration was installed. |
| `execution_time` | `INTEGER` | No | - | Migration execution duration, in milliseconds. |
| `success` | `BOOLEAN` | No | - | Whether the migration completed successfully. |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `flyway_schema_history_pk` | Primary key | `PRIMARY KEY (installed_rank)` |

## Indexes

| Name | Definition | Purpose |
|---|---|---|
| `flyway_schema_history_pk` | Unique B-tree on `installed_rank` | Migration ordering and primary-key lookup. |
| `flyway_schema_history_s_idx` | B-tree on `(success)` | Flyway lookup of failed migrations. |

