# Design: add-multi-source-cakeresume

## Context

**現狀（Phase 001）**
- 只有 Yourator 一個來源，Collector → Fetcher（detail 頁面）→ Normalizer → DB + Discord
- Fetcher 與 Normalizer 的邏輯都是 Yourator-specific（解析 JSON-LD、薪資字串等）
- 推播無篩選，每個新職缺都推到 Discord

**要加的（Phase 002）**
- CakeResume 為第二個來源（search API 一次給完整資料，不需要 detail 頁面）
- 兩個來源的資料格式、API 模式、欄位完全不同

**硬制約**
- 冪等性：`(source, sourceJobId)` unique constraint（D5）
- 無跨平台去重（D6 明確 v1 不做）
- Kafka at-least-once 語義（consumer 必須冪等）
- 不改 Kafka topic 格式版本（只加欄位，遞增 schemaVersion）

## Goals / Non-Goals

**Goals：**
- 支持 per-source 差異化邏輯（Yourator 需要 detail fetcher，CakeResume 不需要）
- Normalizer 層能透明地處理兩個來源（各有自己的 parser，核心邏輯不變）
- 推播去重（同一職缺短時間內不重複推）
- DB 層為 Dashboard 預留欄位（支持搜尋、篩選）
- 架構設計可擴展（未來加第三、第四個來源不爆炸）

**Non-Goals：**
- 職缺關閉偵測（欄位預留，邏輯留 Phase 004）
- 跨平台去重（各平台職缺獨立推播）
- Dashboard 前端（只做 API 層設計）

## Decisions

### D1：Collector 內添加 per-source adapter，而不是拆成獨立服務

**決策**：CakeResumeCollector、YouratorCollector 同在一個 Collector pod，用配置驅動哪些爬（見 D6）

**理由**
- Phase 002 只有兩個來源，分開服務會過度複雜
- 共享 scheduler、metrics、logging 邏輯
- 未來若來源數量爆炸再考慮拆微服務

**被否決的選項**
- 獨立微服務：部署管理複雜，但程式碼清晰度好（保留為 Phase 003+ 的升級路徑）

---

### D2：DiscoveredEnvelope 加 `needsDetail` 欄位

**決策**：每筆 discovered message 帶 `needsDetail: true|false`，告訴 Fetcher 要不要打 HTTP

```json
{
  "schemaVersion": 2,  // 遞增
  "source": "yourator|cakeresume",
  "sourceJobId": "...",
  "needsDetail": true,    // 新欄位
  "url": "...",
  "payload": { ... }
}
```

**理由**
- Fetcher 能簡潔地決定「打 HTTP 還是 pass through」
- 為未來的其他來源留擴展空間（可能需要打多個 detail，或呼叫不同 API）
- Payload 保持原汁原味，不在 Collector 層就強制轉換

**被否決的選項**
- 在 Fetcher 層根據 source 硬編碼邏輯（耦合，新來源要改代碼）
- 在 Collector 層提前轉成中間格式（違反「保留彈性」原則）

---

### D3：Fetcher 使用 Router 模式（per-source strategy）

```
Fetcher consumer 拿到 DiscoveredEnvelope
  ↓
if (needsDetail) {
  YouratorDetailScraper 打 HTML → 提取 JSON-LD → RawEnvelope
} else {
  CakeResumeFetcher no-op → payload 直接轉 RawEnvelope
}
```

**理由**
- 簡單、明確
- Yourator 的限速、重試邏輯集中在 Fetcher（D3 決策）
- CakeResume 的「detail 已完整」體現為 no-op，邏輯清晰

**被否決的選項**
- 統一中間層轉換（在 Collector 層就把兩平台轉成同一格式）：違反「保留彈性」原則

---

### D4：Normalizer 用 Router 模式，註冊 per-source parser

```
Normalizer consumer 拿到 RawEnvelope
  ↓
switch (source) {
  case "yourator":
    parser = new YouratorRawPayloadParser()  // 解析 JSON-LD + 薪資字串
  case "cakeresume":
    parser = new CakeResumeRawPayloadParser()  // 解析 search response
}
  ↓
NormalizedJob = parser.parse(envelope.payload)
  ↓
冪等 upsert → 如果 xmax=0（新插入）才發 NEW event
```

**理由**
- 每個平台的 parser 獨立擁有「如何從平台特定格式提取標準欄位」的邏輯
- 核心 upsert 邏輯（D5）不變
- 容易測試（mock 各平台的 payload、驗證 parser 輸出）

**被否決的選項**
- RawPayloadParser 內部用 switch/if 判斷 source（邏輯混亂）

---

### D5：推播去重用 jobs.upsert 的 xmax 判斷

**現狀**：Normalizer 用 `INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING (xmax=0)` 判斷是否新插入

```sql
INSERT INTO jobs (source, sourceJobId, title, ..., status)
  VALUES (...)
ON CONFLICT (source, sourceJobId) DO UPDATE SET last_seen_at = now()
RETURNING (xmax = 0) AS is_new;
```

- `xmax=0` → 新插入 → 發 NEW event → Notifier 推 Discord
- `xmax>0` → 更新既有（`last_seen_at` 變了，但職缺本身沒變）→ 不發 event

**理由**
- 多來源後，同一職缺可能在短時間內被多個 Collector 輪次發現
- 第二、三輪見到同一職缺時，冪等 upsert 會 UPDATE，不插入新行，`xmax>0`，不推播
- 不需要額外的「推播紀錄表」或「去重 cache」，DB 本身就是真相

**被否決的選項**
- 推播記錄表（重複維護）
- Redis cache（額外依賴）

---

### D6：jobs 表加 status 欄位，為未來 closed sweep 預留

```sql
status ENUM('NEW', 'ACTIVE', 'CLOSED') DEFAULT 'NEW'
```

**邏輯**
- 新職缺插入時 status = 'NEW'
- Normalizer 尚不發 CHANGED event，只記 NEW
- Phase 004（closed sweep）時：
  - 若本輪沒看到的舊職缺 → 標記為 CLOSED → 發 CLOSED event
  - 若之前是 CLOSED 現在又看到 → 改回 ACTIVE

**理由**
- 為未來 closed sweep（D12）預留欄位與邏輯勾子
- 不影響 Phase 002 的推播（依然只發 NEW event）

**被否決的選項**
- 不留欄位（Phase 004 時要 ALTER TABLE，麻煩）

---

### D7：jobs 表加欄位支持 Dashboard 查詢，但不強行標準化

```
新增欄位（可 null，允許某平台沒有）：
- employment_type: VARCHAR  // Yourator: FULL_TIME; CakeResume: full_time
- seniority_level: VARCHAR  // CakeResume: mid_senior_level; Yourator: JSON-LD 沒有
- job_type: VARCHAR         // CakeResume: full_time; Yourator: JSON-LD 沒有
- lang_name: VARCHAR        // CakeResume: English; Yourator: 沒有
- number_of_openings: INT   // CakeResume: 1; Yourator: 沒有
- min_work_exp_year: INT    // CakeResume: 3; Yourator: 沒有

原始數據保留在 raw_documents (JSONB)
```

**理由**
- 支持 Dashboard 的基本查詢（按職位類別、資歷等篩選）
- 保留 null 處理的靈活性（某平台沒有的欄位就是 null）
- raw_documents 保留完整的平台原始數據（未來 LLM 或狀態偵測可用）

**被否決的選項**
- 完全標準化（所有欄位都必填）：無法容納平台差異
- 完全不加欄位，靠 JSONB query：太複雜，查詢慢

---

## Risks / Trade-offs

| Risk | 影響 | 解決方案 |
|------|------|--------|
| **CakeResume API 變更**（搜尋結果格式改）→ Parser 要調整 | 推播停頓 | 提前與平台溝通穩定性；監控 parser 異常、發告警；有 raw_documents 可重放 |
| **多來源推播量變大**（Yourator + CakeResume）→ Discord 頻道吵 | 使用者困擾 | Phase 002 先不做篩選；Phase 003 考慮加 Dashboard 篩選或簡單的推播規則 |
| **Yourator detail 頁面偶爾 render 失敗** → JSON-LD 提取失敗 | 職缺丟失 | Fetcher 已有重試邏輯（D3）；進 DLQ 的失敗入職缺會在下輪補抓 |
| **跨平台同職缺無去重** → 可能推重複 | 資訊重複 | 已決策 v1 接受；Phase 006+ 再考慮（D6） |
| **status 欄位新增** → 舊 query 無法分辨職缺狀態 | API 層可能誤導 | API spec 明確文件化 status 欄位；Dashboard 查詢時預設 WHERE status != 'CLOSED' |

## Migration Plan

### Phase 002 執行順序

1. **DB Flyway migration**（部署前執行）
   - 加 `status ENUM` 欄位（默認 NEW）
   - 加 `employment_type`、`seniority_level`、`job_type`、`lang_name`、`number_of_openings`、`min_work_exp_year`（全部 nullable）
   - 加索引：`(source, status)`、`(title)` 供 Dashboard 搜尋

2. **代碼部署**
   - 新增 CakeResumeCollector、CakeResumeFetcher、CakeResumeRawPayloadParser
   - Normalizer 改 router 邏輯（switch source）
   - DiscoveredEnvelope schemaVersion 升到 2
   - 測試

3. **部署到 k8s**（ArgoCD 同步）
   - 新 image 含 collector + worker + api
   - 無需改 k8s manifests（pod 資源不變）

4. **驗收**
   - 手動查詢 CakeResume 職缺（後端、DevOps）
   - 確認 Discord 有新推播
   - 檢查 DB 的 status、新欄位是否填充
   - 確認 Yourator 推播不中斷

### Rollback 策略

- CakeResume Collector 故障 → 簡單關掉（改 search_queries 表的 enable flag），Yourator 推播繼續
- DB migration 失敗 → Flyway 會自動回滾（新欄位 nullable，舊應用仍可用）

## Open Questions

1. **CakeResume 是否真的不需要 detail API？**
   - 目前判斷 search response 已完整，但未驗證是否等同頁面內容
   - 建議：實作後進行抽樣對比

2. **Yourator detail 的 HTML 解析有沒有邊界情況？**
   - JSON-LD 格式標準，但頁面結構改變時是否穩定？
   - 建議：蒐集失敗案例，優化 Jsoup selector

3. **Discord 推播量的預期？**
   - Yourator + CakeResume 合起來每天大約幾筆新職缺？
   - 會不會影響使用者的使用體驗？
   - 建議：跑 1 周觀察，決定是否加 Phase 003 的篩選功能

4. **Future：跨平台去重的時機？**
   - 同一職缺（如「某公司的後端工程師」）可能在 Yourator 和 CakeResume 都出現
   - 目前各推一次，Phase 006 再考慮 LLM 比對去重
   - 建議：蒐集幾周的重複案例，評估必要性

## 實作層決定

- **Yourator detail 解析**：仍在 Fetcher 層（YouratorDetailScraper）用 Jsoup 提取，不存 HTML 進 Kafka
- **Normalizer 路由**：用 Spring `@Component` 註冊多個 `RawPayloadParser` implementation，Normalizer 中 inject `List<RawPayloadParser>` 路由
- **測試**：CakeResumeListScraper 用 real API response fixture；YouratorDetailScraper 用既有 JSON-LD fixture；Normalizer 用 Testcontainers PG 測冪等性
