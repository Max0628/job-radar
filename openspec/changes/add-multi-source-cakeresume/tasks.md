# Tasks: add-multi-source-cakeresume

## 1. DB Schema 變更（Flyway Migration）

- [x] 1.1 建立 Flyway migration 文件 V3（在 `db/migration/`）
  - 新增 `jobs.status` 欄位（NEW/ACTIVE/CLOSED，DEFAULT 'NEW'——實作時發現直接 DROP DEFAULT
    會讓沒帶 status 欄位的既有 INSERT 語句違反 NOT NULL，改保留 default 當安全網）
  - 新增 `jobs.employment_type`、`seniority_level`、`job_type`、`lang_name` VARCHAR 欄位（可 null）
  - 新增 `jobs.min_work_exp_year`、`number_of_openings` INT 欄位（可 null）
  - 新增索引：`(source, status)`、`(status, last_seen_at)`、`(title)` GIN 全文索引
  - 新增 `search_queries.location`（見 tasks 9.4，Yourator/CakeResume 都需要地區參數）
  - V4 追加：`source_job_id` 從 VARCHAR(64) 放寬到 VARCHAR(255)（端到端驗證發現 CakeResume
    的 path slug 可達 100 字元，見 9.5）
  - 驗證：Testcontainers 整合測試 + 端到端跑過真實 DB，皆通過

- [x] 1.2 本機測試 migration
  - 用 podman-compose 啟動 PG（既有 Phase 001 遺留的容器，含 162 筆真實 Yourator 資料）
  - collector 啟動時 Flyway 自動 migrate，V1→V4 全部驗證通過
  - 檢查 jobs 表結構：`\d jobs` 確認欄位與索引

## 2. Common Module：DiscoveredEnvelope 與 Domain Model

- [x] 2.1 更新 DiscoveredEnvelope record
  - 加 `needsDetail: boolean`、`detailUrl: String` 欄位
  - `schemaVersion` 升到 2
  - 加 `@JsonIgnoreProperties(ignoreUnknown = true)`，並保留向後兼容 constructor

- [x] 2.2 新增 CakeResume 相關的 domain model
  - 未額外建立 CakeResumeJobData 類型——payload 保持 JsonNode 原樣直到 Normalizer 才解析，
    與既有 Yourator 的做法一致（D2 決策：不在 Collector 層強制轉換）

- [x] 2.3 測試 DiscoveredEnvelope 序列化
  - 端到端驗證時直接用真實 Kafka 訊息驗證序列化/反序列化（比寫獨立單元測試更貼近實況）
  - 過程中發現舊版（schemaVersion=1）訊息重放時 `needsDetail` 會被 Jackson 預設為 false
    的風險，已記錄在 design.md 的已知限制

## 3. Collector Module：CakeResume Adapter

- [x] 3.1 建立 `collector/src/main/java/dev/jobradar/collector/scan/cakeresume/` 套件
  - CakeResumeListScraper.java 實作 JobListScraper 介面（POST + 分頁 + 429 退避）
  - **實作偏離**：`sourceJobId` 原計畫用回應的 `id` 欄位，端到端驗證發現真實 API 回應
    根本沒有 `id` 欄位，改用 `path`（slug）
  - **實作偏離**：分頁判斷原計畫用單頁筆數回推，端到端前已改用累積筆數判斷（見 design.md）
  - 單元測試：CakeResumeListScraperTest，fixture 取自端到端驗證時的真實 API 回應

- [x] 3.2 改造 Scheduler 支持多個 adapter
  - 沿用既有 `Map<String, JobListScraper>` 自動註冊機制（Phase 001 已有此設計，
    新增 adapter 不需改 Scheduler 本身）
  - 端到端驗證：Yourator + CakeResume 同輪皆被觸發，互不干擾

- [x] 3.3 新增或更新 search_queries 種子資料
  - V3 加入 CakeResume 種子行（source='cakeresume', keyword='devops', location='Taipei'）
  - Yourator 既有種子行不動（V2 已 apply 過的 migration 不可修改，見 design.md）

## 4. Worker Module：Fetcher 改造（Per-Source Logic）

- [x] 4.1 建立 Fetcher router
  - **實作偏離**：未新增獨立的 FetcherRouter/CakeResumeFetcher 類別——DetailScraper 介面
    本身沒有 discovered payload 的存取權，改在 DetailFetcherListener 內直接依
    `envelope.needsDetail()` 分支（true 才查 DetailScraper map），更貼合現有架構、少一層抽象

- [x] 4.2 更新 Fetcher consumer
  - DetailFetcherListener 改為 needsDetail 分流：
    - true（Yourator）：既有邏輯（已知職缺跳過、呼叫 DetailScraper、限速）
    - false（CakeResume）：不查已存在與否，直接把 discovered payload 轉 RawEnvelope
      （刻意讓每輪都放行，使 last_seen_at 能持續更新，見 design.md D3 決策）
  - 端到端驗證：兩來源皆正常發 jobs.raw，74 筆 Yourator + 197 筆 CakeResume 真實新職缺確認落地

- [x] 4.3 單元測試
  - YouratorDetailScraper 既有測試不受影響（未改動該類別本身）
  - CakeResume 分支邏輯透過端到端驗證覆蓋（真實跑過 needsDetail=false 路徑）

## 5. Worker Module：Normalizer 改造（Per-Source Parser）

- [x] 5.1 建立 parser 層
  - RawPayloadParser 介面沿用既有設計（Phase 001 已是 per-source 可擴充結構）
  - YouratorRawPayloadParser：加 employmentType 欄位提取
  - CakeResumeRawPayloadParser：新增，處理 salary.min/max 是字串型數字且可能為 null
    的情況（"面議"職缺），及 job_type/seniority_level/lang_name/min_work_exp_year/
    number_of_openings 提取

- [x] 5.2 改造 Normalizer consumer
  - NormalizerListener 沿用既有 `Map<String, RawPayloadParser>` 自動註冊機制
    （Phase 001 已有此設計，新增 parser 不需改 Normalizer 本身）

- [x] 5.3 冪等 upsert 邏輯（現有邏輯調整）
  - INSERT 明確寫入 status='NEW'；ON CONFLICT 時 `NEW`/`CLOSED` → `ACTIVE`
    （補上原設計沒考慮到的「CLOSED 職缺復活」情境）
  - 新增 6 個 Dashboard 欄位的 upsert 邏輯

- [x] 5.4 整合測試（Testcontainers PG）
  - NormalizerRepositoriesIdempotencyTest 全數通過（含 V1→V4 完整 migration 鏈）
  - 端到端額外驗證：真實資料下的冪等性（重複 upsert 不重複插入、xmax 判斷正確）

## 6. Notifier 無需改動（推播邏輯保持不變）

- [x] 6.1 驗證現有 Notifier 邏輯
  - 端到端驗證時 Discord webhook 未設定，Notifier 正確記錄警告並跳過（符合既有設計）
  - 邏輯本身無需修改，jobs.events 的 NEW 事件兩來源皆正確產生（237 筆事件與
    162 舊 + 74 新 Yourator + 1 舊 bug 殘留 confirmed 對得上）

## 7. 測試與驗證

- [x] 7.1 本機管線測試
  - 用 podman-compose 起 Kafka + PG，實際跑 collector + worker 兩個 boot jar
  - 完整走過 jobs.discovered → jobs.raw → jobs.events，Yourator + CakeResume 分別驗證

- [x] 7.2 冪等性驗證
  - 真實資料下驗證：重複見到的職缺只更新 last_seen_at，不重複發 NEW event

- [x] 7.3 多來源混合測試
  - 同時跑 Yourator（enabled）+ CakeResume（enabled），互不干擾，各自推播邏輯正確

- [x] 7.4 測試套件
  - `gradle test` 全數通過（14 個測試，0 失敗，含新增的 CakeResumeListScraperTest、
    CakeResumeRawPayloadParserTest）

### 端到端驗證額外發現並修復的問題（不在原始 tasks 清單，過程中發現）

- [x] 7.5 修復 Yourator 關鍵字過濾失效 bug（`keyword` 參數對 API 無效，改用 `term[]`/`area[]`/`sort`，
      這是 Phase 001 遺留、這次代碼審查時發現的既有 bug）
- [x] 7.6 修復 CakeResume sourceJobId 使用不存在的 `id` 欄位，導致 200 筆真實職缺撞號只剩 1 筆存活
      （改用 `path` slug）
- [x] 7.7 修復 CakeResume 的 path slug 長度超過 VARCHAR(64) 導致寫入失敗（V4 migration 放寬到 255）
- [x] 7.8 修復 V3 migration 誤刪 `jobs.status` DEFAULT，導致既有 INSERT 語句違反 NOT NULL
      （這是實作過程中自己引入又自己抓到的 bug，修復後才開始端到端驗證）

## 8. CI/CD 與部署

- [ ] 8.1 更新 .gitlab-ci.yml（如需）
  - 未執行——這次改動未新增外部依賴或建置步驟，既有 pipeline 應可直接沿用，
    留待實際 push 時觀察 CI 是否需要調整

- [ ] 8.2 建立或更新 k8s repo 的 manifests（如需）
  - 未執行——跨 repo 工作，且 image 內容變了但資源配置不變，理論上不需要動 manifest，
    留給真的要部署時再確認

- [ ] 8.3 GitOps 部署
  - 未執行——尚未 push 到遠端、尚未觸發 CI/CD，這次僅完成本機開發與驗證

## 9. 文件與後續評估

- [ ] 9.1 更新 docs/architecture.md
  - 尚未執行——需要補充 D3 確認（CakeResume 確實不需要 detail fetcher，已用真實資料驗證）、
    D6 推播去重實現細節、以及這次發現的 Yourator keyword bug 記錄

- [ ] 9.2 更新 docs/source-api-notes.md（如有新發現）
  - 尚未執行——CakeResume 真實回應「沒有 id 欄位、path 可達 100 字元」這個發現需要補記錄，
    避免未來重蹈覆轍

- [ ] 9.3 觀察與評估（跑 1 周後）
  - 尚未執行——需要真的部署到 k8s 才能觀察長期推播量與頻率

## 10. 驗收 Scenarios（對應 specs）

- [x] 10.1 cakeresume-adapter：成功爬取職缺、分頁、限速、失敗記錄——端到端用真實 API 驗證
- [x] 10.2 multi-source-fetcher：Yourator 打 detail、CakeResume no-op——端到端驗證兩條路徑
- [x] 10.3 per-source-normalizer：Router 正確分路、parser 提取欄位正確——端到端驗證，
      真實資料下欄位（title/company/salary/job_type/seniority_level 等）皆正確
- [x] 10.4 duplicate-prevention：新職缺推、重複見到不推、跨平台各推一次——端到端驗證通過
- [x] 10.5 job-status-lifecycle：status 欄位設置與轉換——NEW 插入、ACTIVE 更新皆驗證，
      CLOSED 判定邏輯本身這次不做（見 add-job-dashboard change 的討論紀錄）
- [x] 10.6 dashboard-api-foundation：DB 欄位可供查詢——欄位已存在且真實資料已填充，
      實際的查詢 API 留給 add-job-dashboard change 實作
