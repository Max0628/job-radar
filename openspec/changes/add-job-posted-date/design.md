## Context

`jobs` 表目前只有 `first_seen_at`（job-radar 第一次發現這筆職缺的時間）跟 `last_seen_at`（最後一次掃描確認還存在的時間），兩者都是我們自己的掃描時間戳，不是平台的真實上架/更新時間。`api` 的 `job-browse-api` 預設排序用 `last_seen_at DESC`（`JobRepository.java` 的 `SORTABLE_COLUMNS` 只有 `lastSeenAt -> last_seen_at` 這一組映射），前端 `JobList.tsx` 也是照這個欄位排序，語意上等於「最近被重新確認還在的排最前」，不是「新職缺排最前」。

兩個來源平台其實都有提供真正的日期，只是從沒被解析、儲存：
- **Yourator**：detail 頁 JSON-LD 有 `datePosted`，格式是 `"2026-07-18 02:00:09 +0800"`（空格分隔、offset 無冒號，非標準 ISO-8601，需要自訂 `DateTimeFormatter`）
- **CakeResume**：search API 每筆資料有 `content_updated_at`，格式是標準 ISO-8601（`"2026-07-20T07:18:33.164382Z"`），可以直接 `Instant.parse`

兩個 parser（`YouratorRawPayloadParser`、`CakeResumeRawPayloadParser`）目前都實作 `RawPayloadParser.parse(JsonNode) -> NormalizedJob`，輸出經 `NormalizerListener` 交給 `JobRepository.upsert(...)` 寫入 `jobs` 表（`ON CONFLICT (source, source_job_id) DO UPDATE`，見 D5 冪等 upsert）。

## Goals / Non-Goals

**Goals:**
- `jobs` 表新增欄位存放平台回報的職缺日期，兩個來源都要填
- `job-browse-api` 預設排序改用這個欄位，讓新職缺真的排在前面
- 缺值（解析失敗、未來新平台沒有這個概念）時優雅降級，不阻斷正規化流程

**Non-Goals:**
- 不重新設計 `first_seen_at`/`last_seen_at` 的既有語意，兩者繼續各司其職（系統面的發現/存活追蹤）
- 不處理「平台事後把日期改成更新時間」這種語意飄移（Yourator 只有 `datePosted` 沒有 `dateModified`，`add-walking-skeleton/design.md` 已確認過，D5 的 `content_hash` 才是變更偵測的依據，這次不重複解決同一個問題）
- 不做歷史資料的強制回填（見下方「既有資料」的取捨）

## Decisions

**欄位命名：`posted_at`（DB）/ `postedAt`（Java/JSON），型別 `TIMESTAMPTZ NULL`**
跟兩個來源的語意最貼近的中性命名；nullable 是必須的，因為解析可能失敗、且既有資料在 migration 當下全部是 null（沒有回溯來源）。

**Migration：`V6__add_job_posted_at.sql`，單純 `ALTER TABLE jobs ADD COLUMN posted_at TIMESTAMPTZ NULL`**
遵照 D6，不需要 backfill migration script（backfill 資料的邏輯屬於一次性維運操作，不是 schema 變更，見下方「既有資料」）。

**`NormalizedJob` 新增 `postedAt` 為最後一個欄位，比照現有模式提供 backward-compat 建構子**
這個 record 已經有兩層 backward-compat 建構子（Phase 001 的 6 欄位版本、add-multi-source-cakeresume 的 12 欄位版本），這次延續同樣模式，新增一個 14 欄位版本（對應目前呼叫端實際用的簽章）作為 backward-compat，主建構子變成 15 欄位帶 `postedAt`。兩個 parser 改呼叫主建構子。

**Yourator 用自訂 `DateTimeFormatter` 解析 `datePosted`；解析失敗吞掉例外、回傳 null，記錄 warning log**
`"yyyy-MM-dd HH:mm:ss Z"`（pattern 字母 `Z` 單一顆對應 `+0800` 這種無冒號 offset 格式）。平台回應格式一旦跑掉（機率低但非零），不該讓整筆職缺的正規化失敗——這跟現有 `CakeResumeRawPayloadParser.parseSalaryValue` 遇到非預期格式時「保守回傳 null 而不是丟例外」是同一個設計原則。

**CakeResume 直接 `Instant.parse(text)`，同樣包 try-catch 回傳 null**
標準 ISO-8601，Jackson/`Instant` 原生支援，不需要自訂 formatter。

**不讓 `postedAt` 流經 `JobEventEnvelope`，不動 `schemaVersion`**
`common` 的訊息 envelope 是給下游 Kafka consumer（目前只有 `worker-notifier` 消費 `jobs.events` 發 Discord）用的；`postedAt` 純粹是給 `api`/前端展示排序用的欄位，沒有任何 Kafka consumer 需要它。這跟 `description`（存在 `attrs` jsonb，同樣不流經 envelope）是同一個先例——`NormalizerListener` 直接把 `NormalizedJob` 寫 DB，envelope 只帶事件本身需要的最小資訊。維持這個邊界，不必要地擴大 envelope 反而增加未來改 schema 的成本。

**`JobRepository`（worker）upsert SQL：`posted_at` 同時進 `INSERT` 跟 `ON CONFLICT DO UPDATE`**
理由跟 `title`/`salary_min` 等欄位一致：職缺被重新掃到時，如果這次解析出的 `postedAt` 跟上次不同（例如上次解析失敗這次成功、或平台真的改了日期），要用最新值覆蓋，不能只在第一次 insert 時寫入。

**`api` 排序：`SORTABLE_COLUMNS` 新增 `postedAt -> posted_at`，預設排序改成 `posted_at DESC NULLS LAST`**
關鍵細節：PostgreSQL 的 `ORDER BY ... DESC` 預設 `NULLS FIRST`——如果不明確加 `NULLS LAST`，既有資料（migration 當下全部是 `posted_at IS NULL`）會全部排到最前面，等於排序完全失效、甚至比現況更糟。這是本次設計最容易漏掉的細節，必須顯式處理。

**既有資料（migration 前已存在的職缺）**
不在 migration 裡強制 backfill，讓它們的 `posted_at` 維持 null（配合上面的 `NULLS LAST`，會自然排到列表最後面，不會佔用最新職缺的版位，行為是可接受的）。`raw_documents` 表存有原始 payload，理論上可以比照這次 session 修 CakeResume URL 的做法寫一次性 SQL 回填，但這屬於維運操作，不放進這次的 schema migration 或程式碼變更範圍——列入 tasks.md 的選用步驟，之後要不要跑由使用者決定。

## Risks / Trade-offs

- **[Risk] `NULLS LAST` 忘記加，既有資料排序時反而全部衝到最前面** → Decisions 已明確記錄，`tasks.md` 會加一個對應的驗收項目（用真的有 null 值的資料驗證排序結果）
- **[Risk] Yourator 的 `datePosted` 格式未來被平台改掉，解析全面失敗** → 已設計成失敗時回傳 null 而非拋例外，不會讓正規化管線掛掉，只是這筆職缺退化成「沒有日期、排最後」，可觀測（warning log）
- **[Trade-off] 既有資料不強制回填，短期內列表最上面幾頁看到的都會是 migration 之後才被重新掃到的職缺** → 可接受，選用的一次性 SQL 回填可以隨時之後補做，不阻塞這次上線

## Migration Plan

1. 部署新的 `V6__add_job_posted_at.sql`（Flyway 自動套用，見 D6，無需手動介入）
2. 部署帶新解析邏輯的 `worker`、新排序邏輯的 `api`、新預設排序的 `frontend`（三個服務各自獨立部署，欄位 nullable，順序不敏感——`worker` 先上線也不會壞，只是 `api`/`frontend` 還沒接住新欄位；反過來也一樣安全）
3.（選用）對既有資料跑一次性 SQL，從 `raw_documents.payload` 回填 `posted_at`
