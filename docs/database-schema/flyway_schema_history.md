# `flyway_schema_history`

## 用途

Flyway 的 migration bookkeeping table，由 Flyway 管理，不是 application business data。

## 欄位

| Column | Type | Nullable | Default | 說明 |
|---|---|---:|---|---|
| `installed_rank` | `INTEGER` | No | - | 已安裝 migration 的排序編號。 |
| `version` | `VARCHAR(50)` | Yes | - | Flyway migration version；repeatable migration 可能沒有 version。 |
| `description` | `VARCHAR(200)` | No | - | migration 的人類可讀描述。 |
| `type` | `VARCHAR(20)` | No | - | Flyway migration type。 |
| `script` | `VARCHAR(1000)` | No | - | Flyway 記錄的 migration script 名稱／路徑。 |
| `checksum` | `INTEGER` | Yes | - | 用來偵測 migration 檔案是否被修改的 checksum。 |
| `installed_by` | `VARCHAR(100)` | No | - | 執行 migration 的 database user。 |
| `installed_on` | `TIMESTAMP` | No | `now()` | migration 安裝時間。 |
| `execution_time` | `INTEGER` | No | - | migration 執行時間，單位是 milliseconds。 |
| `success` | `BOOLEAN` | No | - | migration 是否成功完成。 |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `flyway_schema_history_pk` | Primary key | `PRIMARY KEY (installed_rank)` |

## Indexes

| Name | Definition | 用途 |
|---|---|---|
| `flyway_schema_history_pk` | Unique B-tree on `installed_rank` | migration 排序與 primary key 查詢。 |
| `flyway_schema_history_s_idx` | B-tree on `(success)` | Flyway 查詢失敗的 migration。 |

