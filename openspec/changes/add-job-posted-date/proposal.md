## Why

前端職缺列表目前用 `last_seen_at`（我們最後一次掃描確認這筆職缺還存在的時間）排序，語意錯誤：一筆貼很久、但剛好這次又被重新掃到的舊職缺，會因為 `last_seen_at` 剛更新而排到最上面，蓋過真正新職缺。使用者要的是「新職缺放上面」，需要的是職缺平台自己的上架/更新時間，不是我們的掃描時間戳。

已確認兩個來源平台其實都有提供這個資料，只是目前完全沒有擷取、儲存：Yourator detail 頁的 JSON-LD 有 `datePosted`（D8 調查時就發現，只是當時只拿來確認沒有 `dateModified` 可用），CakeResume search API 每筆資料直接有 `content_updated_at`。

## What Changes

- `jobs` 表新增一個欄位存放平台回報的職缺日期（Flyway migration，遵照 D6 不手動改 schema）
- Yourator payload parser 從 detail 頁 JSON-LD 擷取 `datePosted` 寫入這個欄位
- CakeResume payload parser 從 search payload 讀 `content_updated_at` 寫入這個欄位
- 兩個平台都缺值時（例如未來新平台或解析失敗）允許為 null，不阻斷正規化流程
- `api` 的 `JobResponse` DTO 新增這個欄位吐給前端
- 前端 `JobList` 預設排序改成這個新欄位 DESC（取代 `lastSeenAt`），為 null 的職缺排序時視為最舊（fallback 到 `first_seen_at` 或直接排在最後，設計階段定案）

## Capabilities

### New Capabilities

（無新增獨立能力，本次是既有 job 資料模型與 job-browse-api 的擴充）

### Modified Capabilities

- `job-browse-api`：查詢回應新增平台真實日期欄位，預設排序依據從 `last_seen_at` 改為此欄位

## Impact

- **DB**：新增 Flyway migration（`jobs` 表新欄位）
- **worker**：`YouratorRawPayloadParser`、`CakeResumeRawPayloadParser` 需解析新欄位；`NormalizedJob` 需新增對應欄位
- **common**：若這個欄位需要流經 `JobEventEnvelope`，依 D-envelope 規則要遞增 `schemaVersion`（設計階段確認是否必要——目前傾向不流經 envelope，只在 normalizer 寫 DB 時處理，因為這個欄位對下游 Discord 通知等消費者沒有用途）
- **api**：`JobResponse` DTO、`JobRepository` 排序邏輯
- **frontend**：`JobList.tsx` 預設 `sort` 欄位
