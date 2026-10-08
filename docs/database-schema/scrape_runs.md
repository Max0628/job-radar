# `scrape_runs`

## 用途

每次 collector scan 的 audit 紀錄。保存來源、分類範圍、執行時間、page/job 數量、成功或失敗狀態，以及 scan summary report 的狀態。

## 欄位

| Column | Type | Nullable | Default | 說明 |
|---|---|---:|---|---|
| `id` | `BIGINT` (`BIGSERIAL`) | No | `nextval('scrape_runs_id_seq')` | 這次 scan 的 internal run ID。 |
| `source` | `VARCHAR(32)` | No | - | 被掃描的來源平台。 |
| `query_categories` | `TEXT` | No | - | 本輪使用的 category/profession 清單，以逗號串接保存供 audit 查看。 |
| `started_at` | `TIMESTAMPTZ` | No | - | scan 開始時間。 |
| `finished_at` | `TIMESTAMPTZ` | Yes | - | scan 完成或失敗時間。 |
| `pages_scanned` | `INTEGER` | No | `0` | 本輪實際掃描並採用的 page 數。 |
| `jobs_seen` | `INTEGER` | No | `0` | list response 中觀察到的 job 數量。 |
| `jobs_discovered` | `INTEGER` | Yes | - | 本輪發布或發現的 job 數量；舊欄位名稱為 `jobs_new`。 |
| `status` | `VARCHAR(16)` | No | - | 本輪結果，現有值包含 `running`、`success`、`failed`。 |
| `error_message` | `TEXT` | Yes | - | scan 失敗時的錯誤訊息。 |
| `jobs_deleted` | `INTEGER` | Yes | - | closed-sweep 或後續清理流程使用的刪除／關閉數量欄位。 |
| `scan_mode` | `VARCHAR(8)` | Yes | - | scan 模式，預期為 `light` 或 `deep`。 |
| `terminated_early` | `BOOLEAN` | Yes | - | 是否在真正抵達平台結尾前提早停止。 |
| `report_sent_at` | `TIMESTAMPTZ` | Yes | - | scan summary report 發送時間，用來避免重複回報。 |

## Constraints

| Name | Type | Definition |
|---|---|---|
| `scrape_runs_pkey` | Primary key | `PRIMARY KEY (id)` |

## Indexes

| Name | Definition | 用途 |
|---|---|---|
| `scrape_runs_pkey` | Unique B-tree on `id` | Primary key 查詢。 |
| `idx_scrape_runs_source_started_at` | B-tree on `(source, started_at DESC)` | 依來源查詢最近的 scan history 與 monitoring 資料。 |

