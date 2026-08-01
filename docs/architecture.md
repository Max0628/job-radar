# job-radar 架構藍圖

> 個人用職缺聚合工具：自動爬取各求職平台、正規化後聚合進資料庫，/home/tashuchiu/projects/job-radar/docs/architecture.md
> 新職缺透過 Discord 推播，並提供 API / 前端查詢。
> 部署於個人 homelab K8s（見 `~/projects/homelab-infra/ARCHITECTURE.md`），
> 同時作為 SRE / Infra 方向的面試作品集。
>
> 本文件是所有 spec 的最上位文件。**已決策事項不要重新討論**；
> 執行時若發現決策行不通，先回報並更新本文件，再動程式碼。

## 目標與非目標

**目標**
- 不用再手動刷 Yourator 等平台，新職缺主動推到 Discord
- 職缺資料持續累積（append-only 快照），供之後查歷史、做分析
- 完整走 homelab 的 GitLab CI → Container Registry → ArgoCD GitOps → Prometheus/Loki/Alertmanager 流程

**非目標（v1 明確不做）**
- 商業產品 / 多使用者系統（使用者就是本人＋未來少數同事）
- 職缺消失偵測（closed sweep）——資料欄位先留（`last_seen_at`），邏輯後做
- 跨平台同職缺合併去重（同一缺在兩平台各推一次可接受）
- LLM extraction（接 Threads / Workday 等亂格式來源時才引入，架構留插槽）
- HA / 多副本（homelab 單機，掛了重啟即可）

## 系統總覽

圖例：單線框 `┌─┐` = 服務/process；雙線框 `╔═╗` = Kafka topic。來源框下方標的是
`docs/source-api-notes.md` 目前的驗證現況。

```
    外部求職平台（collector 主動呼叫，遵守禮貌爬蟲：同來源並發≤2、間隔≥1s、429退避）

    ┌────────────────────────┐     ┌────────────────────────┐     ┌────────────────────────┐
    │        Yourator        │     │       CakeResume       │     │           104           │
    └────────────────────────┘     └────────────────────────┘     └────────────────────────┘
        term[]/area[]/sort                  api.cake.me                jobcat/area/keyword
        已驗證可用，正式運作              已驗證可用，正式運作            規格完成，語言選型見 D22
                 │                              │                 ⚠ 需先過 Cloudflare 才能打
                 │                              │                  （cf_clearance/__cf_bm，見 D19）
                 ┴──────────────────────────────┼─────────────────┴
                                                │
                                                ▼           HTTP GET/POST，回傳職缺列表
       ┌──────────────────┐      ┌────────────────────────────┐
       │    Scheduler     │  觸發  │        List Scraper        │
       │   (@Scheduled)   │─────▶│   (collector, per-source   │
       └──────────────────┘      │          adapter)          │
                                 └────────────────────────────┘
                                                │
                                                │                publish：一筆職缺一則
                                                ▼
                                 ╔════════════════════════════╗
                                 ║      jobs.discovered       ║
                                 ║       (Kafka topic)        ║
                                 ╚════════════════════════════╝
                                                │
                                                │                    consume
                                                ▼
                                 ┌────────────────────────────┐
                                 │       Detail Fetcher       │
                                 │  (worker: fetcher group)   │
                                 └────────────────────────────┘
                                                │
                                                │              查PG決定抓/不抓；限速+429退避
                                                ▼
                                 ╔════════════════════════════╗
                                 ║          jobs.raw          ║
                                 ║       (Kafka topic)        ║
                                 ╚════════════════════════════╝
                                                │
                                                │                    consume
                                                ▼
                                 ┌────────────────────────────┐
                                 │         Normalizer         │
                                 │ (worker: normalizer group) │
                                 └────────────────────────────┘
                                                │
                                                │           冪等 upsert + insert-ignore
                                                ▼
                                 ┌────────────────────────────┐
                                 │         PostgreSQL         │
                                 │    jobs / job_snapshots    │ query（唯讀）┌────────────────────┐
                                 │       raw_documents        │─────────▶│   REST API (api)   │
                                 │      scrape_runs ...       │          └────────────────────┘
                                 └────────────────────────────┘                     │
                                                                                    ▼
                                                                         ┌────────────────────┐
                                                                         │      Frontend      │
                                                                         │  (React Admin，     │
                                                                         │  nginx 內部轉發 api） │
                                                                         └────────────────────┘

                                                │
                                                │            NEW/CHANGED 時另外 publish
                                                ▼
                                 ╔════════════════════════════╗
                                 ║        jobs.events         ║
                                 ║       (Kafka topic)        ║
                                 ║       NEW / CHANGED        ║
                                 ╚════════════════════════════╝
                                                │
                                                │                    consume
                                                ▼
                                 ┌────────────────────────────┐
                                 │      Discord Notifier      │
                                 │  (worker: notifier group)  │
                                 └────────────────────────────┘
                                                │
                                                │                    webhook
                                                ▼
                                 ┌────────────────────────────┐
                                 │          Discord           │
                                 │         (channel)          │
                                 └────────────────────────────┘
```

> **圖上「Detail Fetcher」是 per-source 決定，不是每個來源都真的打第二次 request。**
> 實測 Yourator 的 list API 沒有 description，detail 得另外對一個一般網頁 GET、抓內嵌的
> JSON-LD；CakeResume 的 search API 回應已經含完整職缺全文，**已確認不需要 detail 這一步**
> （`needsDetail=false`，見 `CakeResumeListScraper`）。細節與各來源實測結果見
> `openspec/changes/add-walking-skeleton/design.md` 附錄、`docs/source-api-notes.md`。
>
> **104（規格與架構設計完成，未實作，見 Roadmap）**：跟 CakeResume 不同、跟 Yourator 同一類，
> `needsDetail=true`——但 detail 是乾淨 JSON API（`GET /api/jobs/{slug}`），不用像
> Yourator 那樣解析 HTML 裡的 JSON-LD。104 前面有 Cloudflare（Yourator/CakeResume 都沒有，
> 見上圖 104 框下方的 ⚠ 標註），節流規則見 D19，語言選型（Java，不需要 Python）見 D22。

## 排程與掃描機制

這節寫給第一次接手的人看：排程實際怎麼觸發、淺掃深掃差在哪、出錯了誰會知道。決策理由見下方
決策記錄對應的 D 編號。

### 觸發時間：三來源固定整點同時觸發，不是循序等待

三個來源（Yourator、CakeResume、104）**共用同一組固定時間點**（例如 08、10、12、14、16、18、
20 點，各自加隨機分鐘數當 jitter），時間到了**同時**開始各自的掃描，不是排隊依序執行（見 D6）。

「同時」不代表互相干擾：**並發限制是 per-source 的**（例如「同一來源同時只能有 1 個請求在
飛」），三個來源本來就打不同網站，同時進行不會讓任何一個網站收到「疊加」的流量，也不會讓
其中一個失敗拖累另外兩個（既有的 per-query try/catch 隔離機制，跟排程改成並行無關，本來就有）。

### 淺掃 vs 深掃：同一套邏輯，一個開關，不是兩套系統

不維護兩條獨立排程。每次掃描只問一個問題：**「這次要不要提早停止？」**

- **淺掃（預設，每個整點都做）**：翻頁翻到「整頁都是已經看過、內容沒變的職缺」就停止。多數
  情況下很快（可能第 1、2 頁就停了），目的是快速抓到新職缺。
- **深掃（每天挑一個時段做一次）**：關掉提早停止，一定要翻到真的沒有下一頁為止。可能一次
  15 分鐘的預算翻不完（尤其 Yourator/CakeResume 沒有頁數上限），這種情況**用既有但目前沒被
  讀取的 `scrape_cursors.last_page_scanned` 欄位接續**——下次深掃排程觸發時從上次停的頁碼繼續
  翻，不是重新從第 1 頁開始；一路翻到真的翻完，游標才歸零，等下一輪深掃週期重新開始。
  **深掃的單次時間預算比淺掃的 15 分鐘更長**，減少需要跨天接續的次數。**`last_page_scanned`
  只有深掃模式會讀寫，淺掃完全不碰這個欄位**——淺掃提早停止的頁碼跟深掃的接續進度是兩件事，
  混用會讓深掃下次接續到錯誤的位置（見「資料模型草案」）。

**一個必然的結果，不是 bug**：如果某次淺掃剛好從頭到尾都沒有觸發「整頁已知」這個停止條件
（例如當天新職缺多、分散在很多頁），它會自然翻到底，效果跟深掃完全相同。這是預期行為——淺掃
跟深掃本來就是同一套邏輯，差別只在那個開關，淺掃這次剛好沒機會用到那個開關而已。

### 首次掃描的特殊性：任何一個查詢第一次執行，都等於一次深掃

一個查詢第一次執行時，資料庫裡完全沒有這個來源/查詢的任何資料，「這頁是不是整頁都已知」這個
判斷永遠不成立——**第一次掃描不管有沒有開深掃開關，行為上都等於深掃，會一路翻到底**。

這件事對 104 風險最高（有 Cloudflare、沒有自我恢復能力），Yourator/CakeResume 風險較低（有
重試機制擋著、被限速不是致命的），但**深掃機制本身對三個來源都是新的、都沒真正跑過**，上線
時都要謹慎。緩解做法（見 D20）：上線前手動預先跑幾次低頻查詢、把已知職缺 ID 灌進資料庫當
基準值；一開始查詢範圍故意設窄，確認穩定後再逐步擴大；前幾天用更保守的參數；第一次正式執行
親自盯著看，不要無人值守。

### 掃描結果回報：每次掃描完都送 Discord（見 D21）

每次掃描（淺掃或深掃）結束後，送一則報告到 Discord——**跟告警共用同一個頻道**（刻意的選擇，
不是還沒決定要不要分開）。內容包含：來源、模式（淺掃/深掃）、有沒有提早停止、這輪掃了幾筆、
新增幾筆、耗費多少時間、有沒有出錯。**現階段每次都報，就算「這次一切正常、沒新增」也照樣發**
——這會製造不少訊息量（三來源 × 每天約 8 次 ≈ 24 則/天），這是刻意接受的權衡，不是沒考慮到，
之後如果覺得太吵，再改成「只在有新增或出錯時才報」。

「新增幾筆」這個數字**不是掃描結束當下就知道**——List Scraper 發布到 Kafka 就結束了，真正
判斷新舊是 Normalizer 非同步做的事。做法：**延遲一段時間（約 10 分鐘，比 SLO-1 的 5 分鐘
pipeline 延遲留餘裕）後，查資料庫「這個來源這個時間窗內 `first_seen_at` 有幾筆」當作新增數**
——這是簡化版，準確度依賴掃描時間點不要太密集（目前固定整點、間隔 2 小時，風險低），沒有做
到用 run id 精確關聯每筆訊息屬於哪一輪掃描（那樣更準，但要改動 Kafka 訊息格式，代價更高）。

### 錯誤處理與告警：三來源通用，104 額外加強（見 D19）

任何一次掃描失敗（重試過仍失敗，代表不是偶發抖動），**單次就觸發告警**，不用等累積或持續一段
時間，因為既有的重試機制已經先濾掉偶發問題了。錯誤分三類：疑似風控（403/503/回應非 JSON）
不重試、直接標記不健康；一般性暫時錯誤（429/5xx/逾時）比照既有的 exponential backoff 重試；
其他非預期錯誤記 log 不升級。104 因為有 Cloudflare、沒有自動復原能力，告警訊息額外帶更細的
分類原因，Yourator/CakeResume 用既有的錯誤訊息文字就夠診斷。

## 決策記錄（含被否決的選項）

| # | 決策 | 理由 | 被否決的選項 |
|---|------|------|--------------|
| D1 | Java 21 + Spring Boot 3.x，virtual threads，blocking style | 使用者主力語言；workload 是低量 IO-bound，效能非選型軸；生態成熟（spring-kafka、Resilience4j、@Scheduled） | Go（SRE 訊號改由平台層提供，避免三線作戰）；WebFlux（複雜度稅，明確禁用） |
| D2 | Kafka 單 broker（KRaft mode）做服務解耦 | 使用者要練 Kafka 且當面試素材；topic/consumer group 對應解耦模型 | NATS JetStream（更輕但學習價值較低）；PG-based queue（耦合） |
| D3 | 兩段式爬蟲：list scraper（發現）＋ detail fetcher（抓全文）；**是否真的需要 detail 這一段是 per-source 決定**（Yourator 需要；CakeResume 的 list 已含全文，可能不需要，見 design.md 附錄） | detail 貴、list 便宜；限速集中在 fetcher；單筆重試；平台改版只修薄薄的 scraper | 單段式每輪全抓 detail（request 爆量）；scraper 直連 DB（職責過厚） |
| D4 | PostgreSQL 單庫：核心欄位正規化 + JSONB 放平台專屬欄位；`job_snapshots` append-only；raw payload 也落 PG | 職缺量級（全台十萬級）不需要 NoSQL；SQL 查詢/索引/交易保留；raw 可重放（改 parser / 之後上 LLM 不用重爬） | MongoDB（多養一套系統無收益）；raw 落檔案系統（k8s 內不如落 PG 方便） |
| D5 | Worker 全部冪等：jobs 以 `(source, source_job_id)` unique upsert；快照 insert-ignore；diff 事件以 DB 狀態為準 | Kafka at-least-once，訊息必然重複；冪等做對則任意重試/重放皆安全 | 以「收到訊息」判斷新缺（會重複推播） |
| D6 | **排程模型改版**（取代原本的雙節奏構想，見「排程與掃描機制」章節完整說明）：三來源固定整點（如 8/10/12/14/16/18/20 點 + jitter）**同時並行**觸發，取代循序排隊；淺掃/深掃不是兩套系統，是同一套掃描邏輯的一個「提早停止」開關——淺掃預設開（整頁已知即停）、深掃每天一次關掉（翻到真的沒有下一頁），深掃可跨天用既有但目前未使用的 `scrape_cursors.last_page_scanned` 接續，深掃有自己更長的單次時間預算 | 並行不影響安全性：並發限制是 per-source 的，三個來源本來就打不同網站，同時進行不算「疊加流量」；既有的 per-query try/catch 隔離跟排程改並行無關，本來就有；淺掃深掃收斂成一個開關，讀者只要理解一套邏輯，不用同時維護兩條排程的心智負擔；深掃用接續而非每次重新第一頁開始，是因為深掃的目的就是「完整覆蓋」，冪等 upsert（D5）讓接續產生的漂移風險低、可接受 | 維持循序執行（無安全上的必要，只會拉長最壞情況的總耗時）；深掃排在凌晨（跟 08:00–23:00 活躍時段設計互相矛盾，凌晨掃描才是真正不自然的流量模式）；深掃每次重新從第 1 頁開始（大結果集會永遠翻不到深頁，形成永久遺漏）；深掃跟淺掃各自獨立排程/獨立程式路徑（兩套系統要分別維護，複雜度不成比例） |
| D7 | Gradle multi-module monorepo，3 個可部署單元：collector / worker / api | 共享 envelope 合約與 domain；解耦是部署層級的事，不用 repo 邊界表達 | 一服務一 repo（合約跨 repo 發版之痛）；全部塞一個 boot app（失去獨立部署自由） |
| D8 | worker 內三個 consumer（fetcher / normalizer / notifier）各用獨立 consumer group | 之後拆成獨立 pod 只改部署描述、不改程式碼 | — |
| D9 | Image registry 用自架 GitLab Container Registry（`registry.192.168.100.200.nip.io`） | 已啟用、CI 原生整合（CI_REGISTRY_* 自動注入）、內網快、私有、無 rate limit | Docker Hub（image 公開、走外網、pull limit） |
| D10 | Secrets 用 Sealed Secrets（kubeseal） | GitOps 環境標準輕量解；加密後 yaml 可進 git 由 ArgoCD 同步 | Vault / External Secrets（homelab 維運過重）；明文 Secret 手動 apply（脫離 GitOps） |
| D11 | 事件發布 v1 接受「commit PG 後、發 Kafka 前 crash 會漏事件」 | 個人工具可接受，下一輪爬取會補；v2 若要嚴謹再上 transactional outbox | v1 直接上 outbox（過早複雜化） |
| D12 | 職缺消失偵測延後，但 `last_seen_at` 與 `scrape_runs` 從 v1 就記錄 | 偵測邏輯依賴的資料不可事後補 | — |
| D13 | frontend 對外唯一入口，`api` 完全不對外開 Ingress；frontend 的 nginx 用內部 Service DNS 反向代理 `/api/*` 給 `api`（同源請求，瀏覽器角度沒有跨網域） | 攻擊面最小化——公開只暴露一個服務；跟本機開發用 Vite proxy 是同一個設計決策的 production 對應實作，`frontend/.env` 的 `VITE_JSON_SERVER_URL=/api` 不用因環境而改 | `api` 也開一個公開 Ingress、走 CORS 讓瀏覽器直連（多一個公開端點、多一層 CORS 設定與維護成本，資安收益為負） |
| D14 | GitLab CI 用 `rules: changes:` 讓 `package:*`/`deploy` 只處理真的有異動的服務；worker 的 upsert SQL 明確把每個會變動的欄位都寫進 `ON CONFLICT DO UPDATE SET`，不能只挑「看起來會變」的欄位 | 四個服務每次 push 全部重建，單一服務改動也要等其他三個跑完；`ON CONFLICT` 漏欄位是個沉默的 bug（該欄位永遠卡在第一次 insert 的值，之後重新整理進來的正確資料完全不會覆蓋上去），已實際踩過（`url` 欄位一度被漏掉） | 全部服務永遠一起建置（簡單但浪費）；upsert 只更新「常見會變」的欄位（隱性假設不成立時會安靜壞掉，難以事後察覺） |
| D15 | Tracing 後端選 Tempo，部署形態比照既有 Loki（SingleBinary + filesystem storage） | 與既有 Grafana/Loki 同生態，共用查詢介面；trace ↔ log ↔ metric 互跳在 Grafana 全家桶內最順；規模小、無 HA 需求，跟當初選 Loki 不選 ELK 同一套理由 | Jaeger（獨立的 UI/查詢介面，會多一個要維護、要記的工具，homelab 規模用不到它額外的功能）；Zipkin（生態較舊、跟 Grafana 系整合不如 Tempo 原生） |
| D16 | 不部署獨立的 OTel Collector，應用程式直接用 OTLP exporter 送到 Tempo | 少一個要維護的元件；這個規模（3 個服務、單一後端）用不到 Collector 的 fan-out／批次聚合／protocol 轉換等價值，直接點對點送最簡單 | 部署 OTel Collector 做 buffering/fan-out（過早引入複雜度，homelab 規模沒有多後端、多來源的需求） |
| D17 | 用 Micrometer Tracing（Spring 生態原生）而非 Java Agent（如 OTel Java auto-instrumentation agent） | 跟既有的 Micrometer metrics（`micrometer-registry-prometheus`）同一套 API 心智模型；不需要額外掛 `-javaagent`、不用管 agent 版本跟 Spring Boot 版本的相容性；`RestClient.Builder`／`KafkaTemplate` 這類 Spring 自動組態的元件天生就能被 observation 機制接住，不需要 agent 做 bytecode instrumentation | Java Agent（zero-code，但這個規模的程式碼量小，手動埋點的心智負擔不高，換來的是不用管 agent 相容性、啟動參數這類額外維運成本） |
| D18 | 100% 取樣率 | 這個 homelab 規模（單機、低流量、個人使用）不需要取樣就能負擔，且完整保留每一筆 trace 對除錯最有利；**這是這個規模的特例，絕對不可外推到生產環境**——生產環境的流量會讓 100% 取樣的儲存與網路成本失控，需要用 head-based 或 tail-based sampling | 固定比例取樣（例如 10%）：規模用不到，還會讓少量、關鍵的除錯情境（例如這次抓到的 Discord webhook 失敗）有機率被漏採 |
| D19 | **錯誤處理與告警規則（三來源通用機制 + 104 專屬加強）**——通用部分：任何來源任一次掃描失敗（重試過仍失敗）→ **單次即觸發告警**，不用等累積次數或持續時間（既有重試機制已先濾掉偶發抖動，能被記成「失敗」的都已經是真的問題）。錯誤分三類，不是統一重試策略：(A) 疑似 Cloudflare/風控相關（`403`、`503`、回應非 `application/json` 但狀態碼 200）→ **不重試**，標記來源不健康，104 等人工重新從瀏覽器貼 `cf_clearance`/`__cf_bm`（**不做自動解題**）；(B) 一般性暫時錯誤（`429`、`500`/`502`/`504`、連線逾時）→ 比照既有的 exponential backoff 重試，最多 3 次；(C) 其他非預期錯誤 → 記 log + anomaly 計數器，不重試、不升級，留給下一輪排程。104 專屬加強：時段限制（僅 08:00–23:00）、同來源並發＝1（非其他來源的 ≤2）、翻頁間隔 3–10 秒 random jitter、獨立每日請求量上限；告警訊息額外帶分類原因（`jobradar.scrape.anomaly{source="104", reason="cloudflare_blocked"}`），Yourator/CakeResume 用既有錯誤訊息文字即可，不需要這層額外分類。既有的 `JobRadarSourceSilent`（6h 靜默）保留當備援 | 104 前面有 Cloudflare（Yourator/CakeResume 皆無），且背景偵測腳本確認持續運作中；架構明確排除 Playwright，沒有自動過 JS Challenge 的能力，一旦被出題就是死路；「單次即告警」原本只設計給 104，後來決定通用化——三個來源都需要「及時知道哪個平台出問題」，且既有重試機制已經吸收了偶發抖動，通用化不會太敏感；detail fetch 量會隨新職缺數而非頁數成長（比 list 更值得限速）；把 403/503 這類疑似風控訊號跟 429/5xx 這類一般性暫時錯誤分開處理，是因為兩者重試的代價不對稱；6h 靜默的既有告警反應太慢，見 `source-api-notes.md` | 只給 104 做單次即告警、Yourator/CakeResume 沿用舊的聚合式規則（三來源都需要及時知道，沒理由只給一個來源）；套用跟 Yourator/CakeResume 一樣寬鬆的節流/重試參數在 104 上（風險不對稱：104 沒有自動復原機制，出事代價比其他兩來源高很多）；用 Playwright 自動解 Challenge（明確排除，見非目標與 POC 規格） |
| D20 | **首次掃描風險緩解**：任何查詢第一次執行等同一次深掃（見「排程與掃描機制」）。上線流程：(1) 上線前手動、低頻地預先查詢幾次，把已知職缺 ID 灌進資料庫當基準值，讓早停機制第一次排程執行時就有東西可比對；(2) 查詢範圍（`jobcat`/`area`）一開始故意設窄，確認穩定運作幾天後再逐步擴大；(3) 上線初期用比正常值更保守的參數（更長 jitter、更小單次頁數上限）；(4) 第一次正式排程執行親自盯著 log/Grafana，不無人值守。三來源皆適用（深掃機制本身對三者都是全新的），104 因為 Cloudflare 風險最高、最需要嚴格執行這個流程 | 「第一次掃描＝深掃」是排程模型的必然結果，不是可以繞過的邊角案例；104 沒有自我恢復能力，一旦上線第一天就被擋，後續沒有補救手段；Yourator/CakeResume 雖然風險較低（有重試機制），但深掃這個機制本身也從沒真正驗證過，同樣值得謹慎 | 直接套用正常排程參數让新查詢跑第一次（104 風險不可接受：可能上線當天就被擋，且無法自動恢復） |
| D21 | **掃描結果回報 Discord**（新元件，非既有功能延伸）：每次掃描（淺掃/深掃）結束後送一則報告，**跟告警共用同一個 Discord 頻道**（刻意選擇，不分開）。內容：來源、模式（淺掃/深掃）、是否提早停止、掃描筆數、新增筆數、耗時、成功/失敗。**現階段每次都報**（含「一切正常」），之後可能改成只在有新增或出錯時才報，先不做這個優化。新增筆數用簡化版算法：延遲約 10 分鐘（讓 SLO-1 承諾的 5 分鐘 pipeline 延遲有餘裕）後查 `jobs` 表「這個來源這個時間窗內 `first_seen_at` 有幾筆」，不做精確的 run id 逐筆關聯 | List Scraper 掃描結束當下只知道總筆數，不知道新舊——判斷新舊是 Normalizer 非同步處理的事，中間隔著 Kafka，這是既有架構的既有限制，不是這次才發現；簡化版時間窗查詢在目前固定整點、間隔 2 小時的排程下漂移風險低，換取比 run id 精確關聯低很多的實作成本 | 掃描結束當下就回報新增數（技術上做不到，pipeline 非同步）；用 run id 精確關聯每筆訊息屬於哪一輪掃描（更準，但要幫 `DiscoveredEnvelope`/`RawEnvelope`/`JobEventEnvelope` 都加欄位，改動範圍大，現階段不成比例）；只在有新增/出錯時才報（使用者現階段明確要求先每次都報，之後再優化） |
| D22 | **104 collector 用 Java，不需要 Python/`curl_cffi`**：`104-api-poc` 額外用純 Java 21 `java.net.http.HttpClient`（無任何 TLS/JA3 指紋模擬）單次測試 104 list API，取得 200 與正確 JSON，結果跟先前 `curl_cffi` 版本一致。**未來 contingency（不預先實作）**：如果之後 Cloudflare 升級、Java 版本開始被擋，且診斷後確定原因是 TLS 指紋，才評估 Netty + 原生 SSL（`netty-tcnative`/BoringSSL）或 Conscrypt 提升 Java 端指紋擬真度，或退回已驗證可行的 Python/`curl_cffi`——後者因為既有 per-source adapter 模式（`JobListScraper`/`DetailScraper` 介面）本來就把每個來源的實作隔開，屆時只需替換 104 這一份，不影響 Yourator/CakeResume | 維持 D1「全部 Java」的既有決定，不多養一套語言/部署單元的維護成本；實測結果不支持「一定要 TLS 指紋模擬才打得通」這個假設，之前的顧慮是基於還沒驗證的猜測；被擋是否真的因為 TLS 指紋是不確定的（也可能是請求量/IP 信譽等其他訊號），現在為了一個不確定會不會發生、發生原因也不確定的問題預先換語言，不符合「先接受、真的遇到再處理」的既有哲學（見 D11 同一種思路）；出問題時的應對方式（D19 標記不健康、等人工介入）本來就跟語言無關，不依賴「換更強的指紋偽裝」去自動恢復 | 現在就用 Python/`curl_cffi` 或「Java 呼叫小型 Python 服務」的混合架構（解決一個尚未證實存在的問題，且真的需要時代價可控，不用預先付這筆成本） |
| D23 | **共用掃描常數抽離**：`MAX_RETRY`（3）、`MAX_SCAN_DURATION`（15 分鐘）、退避公式（`2000L * attempt`）目前逐字重複在 `YouratorListScraper`/`CakeResumeListScraper` 兩個檔案裡，104 實作時要抽成共用結構，但**要支援 per-source 覆寫**，不是單一全域常數——104 的值本來就不一樣（並發＝1、jitter 3–10 秒、深掃有自己的時間預算、D19 的三類錯誤分類跟另外兩個來源的統一重試邏輯不同） | 現有的複製貼上已經是 DRY 違反，104 若比照複製第三份會讓問題更明顯；三來源需要的值本來就不同，抽離時如果做成單一全域常數會擋住 104 需要的差異化參數，等於解決了重複問題卻製造新的彈性不足問題 | 現在就動手重構（這次是純文件討論，不動程式碼；留到 104 實作時一併處理，避免沒有實際用例驅動下的過早抽象） |
| D24 | **前端：104 直接套進既有的 facets 機制，不新增設計**——`SearchQueryForm.tsx` 的 `SOURCE_CHOICES` 加一筆 104；新增 `104FacetsClient`（比照 `YouratorFacetsClient`/`CakeResumeFacetsClient` 的既有介面），讀 `Area.json`/`JobCat.json` 轉成 `{id, name}` 格式供 `GET /api/sources/104/facets` 回傳（既有 12 小時快取機制沿用，甚至可以更簡單——開機讀一次存記憶體，這兩個檔案變動極少，不用真的定期重打 104）；`CategoryAndLocationInputs` 的 source 判斷式（現在只有 CakeResume/其他兩分支）多加一個 104 專屬分支，因為 Yourator 分支現有的「至少選 2 個分類」警告文字是 Yourator 專屬限制，104 不適用；`SearchQuery` TypeScript 型別的 `source` union 加上 `"104"`。**不新增**：淺掃/深掃是系統自動判斷（見 D6），不需要輸入欄位；掃描回報歷史畫面（D21 已經走 Discord，重複做一個網頁畫面顯示同樣的東西不成比例，先跳過） | 現有的 `useSourceFacets`/`FacetsService` 架構本來就是為了「分類/地區選單不要寫死、要能反映平台真實情況」設計的，104 完全符合這個既有動機，用同一套機制而不是另外發明；`intervalMinutes` 欄位語意在新排程模型下不變（只是「什麼時候檢查該不該掃」從輪詢變成固定整點檢查，跟這個欄位本身無關），不用改 | 104 另外設計一套獨立的前端輸入元件（不必要，既有機制直接夠用）；現在就做掃描回報歷史的專屬畫面（Discord 已經覆蓋這個需求，重複建置不划算） |

## Repo 結構（Gradle multi-module）

```
job-radar/
├── CLAUDE.md            # 給執行 session 的守則
├── docs/                # architecture.md（本檔）、source-api-notes.md
├── openspec/            # SDD change 文件（proposal/design/specs/tasks）
├── settings.gradle
├── common/              # 訊息 envelope、domain model、Flyway migration（不可執行）
├── collector/           # Scheduler + 各平台 list scraper adapter（boot jar）
├── worker/              # detail-fetcher / normalizer / notifier 三個 Kafka consumer（boot jar）
├── api/                 # REST API（boot jar）
├── frontend/             # React Admin 前端（靜態 build，nginx 服務，見 D13）
└── .gitlab-ci.yml       # test → build → package（kaniko）→ deploy（更新 k8s repo image tag）
```

## Kafka Topics 與訊息合約

| Topic | Producer → Consumer | 內容 |
|-------|--------------------|------|
| `jobs.discovered` | collector → worker(fetcher) | list 摘要，一筆職缺一則 |
| `jobs.raw` | worker(fetcher) → worker(normalizer) | 完整 detail 原始 payload |
| `jobs.events` | worker(normalizer) → worker(notifier)、未來其他訂閱者 | `NEW` / `CHANGED`（未來 `CLOSED`） |

Envelope（common module 內定義，欄位不可少）：

```json
{
  "schemaVersion": 1,
  "type": "discovered | raw | event",
  "source": "yourator | cakeresume | ...",
  "sourceJobId": "...",
  "scrapedAt": "ISO-8601",
  "url": "...",
  "payload": { "平台原始回傳或事件內容": "..." }
}
```

- 序列化：JSON（量小，可讀性優先；不用 Avro/Schema Registry）
- 失敗處理：重試 N 次後進 `<topic>.dlq`；DLQ 深度 > 0 觸發告警

## 資料模型草案（細節由 feature spec 定案）

- `jobs`：現況表。`(source, source_job_id)` unique。核心欄位：company、title、salary_min/max、url、first_seen_at、last_seen_at、status；`attrs JSONB` 放平台專屬欄位；`content_hash` 供變更偵測
- `job_snapshots`：append-only，`(source, source_job_id, scraped_at)` unique，重複 insert-ignore
- `raw_documents`：detail 原始 payload（JSONB/text），供重放
- `scrape_runs`：每輪執行記錄（source、query、起訖、抓到幾筆、成功/失敗）——同時是監控素材與未來 closed sweep 的依據。**現有欄位（`id`/`source`/`query_keyword`/`started_at`/`finished_at`/`pages_scanned`/`jobs_seen`/`jobs_new`/`status`/`error_message`）不夠支撐 D21 的 Discord 回報**——回報要顯示「這輪是淺掃還是深掃、有沒有提早停止」，這兩個資訊目前沒有欄位可以存，104 實作時要新增，例如 `scan_mode`（`light`/`deep`）、`terminated_early`（boolean）
- `search_queries`：設定表（source × 分類 × 頻率），scheduler 據此觸發
- `scrape_cursors`：每個 query 的游標，目前有 `last_scanned_at`（上次掃描時間，決定下次何時該掃）與 `last_page_scanned`（目前寫入但**沒有被讀回來用**）。D6 決定要真的用上 `last_page_scanned` 做深掃接續，但**語意要限定清楚：只有深掃模式才讀寫這個欄位，淺掃完全不碰它**——如果淺掃也寫入，會把深掃的接續進度覆蓋掉（淺掃提早停止的頁碼不代表深掃真正翻到的深度，兩者混用會讓深掃下次接續到錯誤的位置）

## 部署與 GitOps

- Manifests 放 `k8s` repo 的 `apps/job-radar/`（純 YAML，無 kustomize——ArgoCD root app 是
  `directory.recurse: true`，遞迴同步整個目錄即可，不需要逐 app 建 ArgoCD Application 或用
  kustomize 收攏，見 `add-walking-skeleton/design.md` 的實際確認）：collector / worker / api /
  frontend 四個 Deployment + Kafka、PostgreSQL 兩個 StatefulSet + SealedSecrets（DB 密碼、
  Discord webhook、registry pull 用的憑證）。frontend 額外掛一個 Ingress（`cert-manager` 自動
  簽發 TLS，見 D13）
- CI 流程：push → `test`（全模組跑一次）→ `build`（Java 三服務 bootJar，跟 `build:frontend`
  平行跑）→ `package:*`（kaniko 打包四個 image，各自用 `rules: changes:` 判斷這次有沒有真的
  改到自己負責的資料夾，沒改到就跳過，不浪費時間重建沒變動的服務）→ `deploy`（用同一套
  `rules: changes:` 邏輯判斷要更新 k8s repo 裡哪幾個服務的 image tag，`needs: [test, build]`
  確保不會在測試/編譯結果出來前搶跑）→ ArgoCD 自動 sync
- CI 的 Gradle / npm 套件下載會快取到 MinIO（GitLab 自帶的 S3 相容儲存，不是另外新裝的服務）；
  Gradle 端要另外把 `GRADLE_USER_HOME` 指到專案資料夾內，不然 Gradle 預設存在使用者家目錄，
  跟快取路徑對不上、快取形同虛設（真的踩過的坑）
- 資源預算：實體機是 T480（8C/62G），但 k8s 三個節點是這台機器上開的虛擬機（`k8s-control`/
  `k8s-worker1`/`k8s-worker2`，各只分到 2 vCPU），CPU 常態吃緊，排程 pod 遇到
  `Insufficient cpu` 是已知常態；所有 JVM 設好 `-Xmx`（各 512MB 內）與 k8s requests/limits；
  Kafka heap 1GB 內

## 可觀測性

**2026-07-28/29 更新：以下已從規劃變成實作完成，細節見
`openspec/changes/add-platform-observability`、`add-business-metrics-and-alerting`。**

- Metrics：Micrometer + Prometheus endpoint，接既有 kube-prometheus-stack
  （ServiceMonitor）。分兩條路徑：
  - **Path A**（不改 Java code）：`postgres_exporter` 自訂查詢直接聚合
    `scrape_runs` 表，提供各來源掃描成功率／發現筆數／最後成功時間
  - **Path B**（Micrometer 埋點）：`jobradar.scan`／`jobradar.jobs.discovered`／
    `jobradar.scan.duration`（collector）、`jobradar.parse`／
    `jobradar.events.published`／`jobradar.notification`／
    `jobradar.pipeline.latency`（worker）、`jobradar.scrape.retry`（兩個
    list scraper 的 429 重試）
  - 平台層：`kafka-exporter`（broker 端 consumer lag，比 client 端可靠——worker
    完全掛掉時 client 端指標會消失而非增長）、`postgres_exporter` 標準指標
- Logs：結構化 JSON logs（`LogstashEncoder`）→ 既有 Promtail/Loki
- Alerts（Alertmanager，經 `AlertmanagerConfig` CRD 路由，一個 catch-all
  receiver 涵蓋全叢集）：`JobRadarSourceSilent`（6h 靜默失敗）、
  `JobRadarScanSuccessRateLow`（SLO-2）、`JobRadarDlqNotEmpty`、
  `JobRadarPipelineLatencySLOBurnFast`/`Slow`（SLO-1）、
  `JobRadarNotificationFailureRateHigh`、`JobRadarConsumerLagGrowing`、
  `JobRadarPostgresConnectionsHigh`，皆有 `promtool test rules` 單元測試
  （見 `k8s` repo `apps/job-radar/tests/`）
- SLO：
  - **SLO-1**：99% 的職缺事件從 `scrapedAt` 到 Discord 推播成功耗時 < 5 分鐘
  - **SLO-2**：每個來源每日掃描成功率 ≥ 95%
- Dashboard：`job-radar Pipeline`（Grafana，ConfigMap as code），pipeline 漏斗
  + SLO 達成率 + DLQ 深度 + consumer lag
- **Traces（2026-07-29 上線）**：Micrometer Tracing（OTel bridge）+ OTLP，送到
  `k8s` repo 部署的 Tempo（SingleBinary + filesystem storage，見
  `homelab-infra/ARCHITECTURE.md`）。collector 與 worker 兩個服務埋點，`api`
  不在範圍內（純 REST/DB 查詢，不在非同步 pipeline 上）。100% 取樣（這個規模
  的特例，不可外推到生產環境）。
  - Kafka context 傳遞：producer 端用 `spring.kafka.template.observation-
    enabled=true`（KafkaTemplate 是自動組態的，屬性就夠）；worker 手動建立的
    `ConcurrentKafkaListenerContainerFactory`（見 `KafkaConsumerConfig`）只設
    屬性不會生效，要在 `factory.getContainerProperties()` 明確開
  - traceId/spanId 透過 Micrometer Tracing 對 MDC 的內建整合自動出現在
    `LogstashEncoder` 的 JSON log 輸出中，**不需要改 `logback-spring.xml`**
    （實測確認，假設成立）
  - `DiscordNotifier` 的 `RestClient.Builder` 注入（`add-business-metrics-
    and-alerting` 已完成的改動）自動獲得 client span，不需額外埋點——實測確認
  - **端到端實測驗證**：手動把某個 `search_queries` 的 `scrape_cursors`
    往前撥（觸發立即掃描，不用等自然的 2 小時間隔），在 Tempo 抓到一條涵蓋
    622 個 span、橫跨 collector 與 worker 兩個服務的單一 trace：
    `scan-scheduler.tick` → 10 次 `http post`（CakeResume 分頁 API）→ 200 筆
    `jobs.discovered send` → worker `jobs.discovered receive` → `jobs.raw
    send/receive` → `jobs.events send/receive` → `DiscordNotifier` 對外
    `http post`（4 次，1 次原始嘗試 + 3 次重試）→ `jobs.events.dlq send`。
    完整驗證了「單一 trace 涵蓋完整跳轉」（不是四條互不相連的獨立 trace），
    也是 tracing 上線後第一次派上用場：4 次 `http post` span 的
    `exception: IllegalArgumentException`、`http.url: REPLACE_ME` 屬性
    精確重現了 `job-radar-discord` webhook 仍是 placeholder 這個已知問題
    （見上方前置作業與待決事項），不用再翻 log 就能立刻定位到確切原因
  - Grafana 已接 Loki ↔ Tempo 雙向 derived field（`traceId` 正則
    `"traceId":"(\w+)"`），log 與 trace 可互跳（見 `homelab-infra/
    ARCHITECTURE.md`）

## 前置作業（在 homelab-infra 側）

1. **CA 信任缺口**：`install-registry-ca-trust.yml`（homelab-infra）把 homelab Root CA 裝進
   k8s node 的系統信任庫 → `update-ca-certificates` → 重啟 containerd，不然節點拉不了 registry
   image。**只對 `k8s-control`/`k8s-worker1` 執行過，`k8s-worker2` 還沒補**（見待決事項）
2. cluster 安裝 Sealed Secrets controller——已完成，`kubectl apply` 官方 release（這台機器沒裝
   helm，見 `add-walking-skeleton` tasks.md 的實作偏離記錄）
3. GitLab 上建立 `job-radar` project，確認 Runner 可用、Registry 可 push——已完成
4. ~~建立 Discord server + webhook，URL 以 SealedSecret 管理——已完成~~
   **2026-07-29 修正：這句話是錯的，從未真的完成過。** `job-radar-discord`
   這個 SealedSecret 解出來的值一直是字面上的 `"REPLACE_ME"`
   （`secrets.example.yaml` 的 placeholder），從未被換成真的 webhook URL。
   這個問題完全沒被發現，直到 `add-business-metrics-and-alerting` 新增的
   `JobRadarDlqNotEmpty` 告警上線，才發現 `jobs.events.dlq` 早已默默累積
   207+ 筆訊息（推播全部失敗，因為 URL 不合法）。**這是「文件寫已完成不代表
   真的驗證過」最直接的案例**——待辦：建立真的 Discord webhook，重新 seal
   `apps/job-radar/discord-sealed-secret.yaml`（見
   `homelab-infra/TROUBLESHOOTING.md`「job-radar-dlq」章節）

## Roadmap

| Phase | 內容 | Spec | 狀態 |
|-------|------|------|------|
| 001 | Walking skeleton：Yourator 單一關鍵字走通全管線到 Discord，部署進 cluster | `openspec/changes/add-walking-skeleton`（待歸檔） | **已完成並上線**，端到端驗收（push → CI → ArgoCD → Discord）已於實際 pipeline 驗證通過 |
| 002 | 多來源 adapter；search_queries 多關鍵字 | `openspec/changes/add-multi-source-cakeresume`（待歸檔） | **已完成並上線**——CakeResume 作為第二來源已上線 |
| 003 | REST API + 前端看板 | `openspec/changes/add-job-dashboard`（待歸檔） | **已完成並上線**：`api` 唯讀查詢端點、React Admin 前端（職缺瀏覽、search_queries 配置台、收藏），部署見 D13 |
| 004 | 職缺消失偵測（closed sweep）+ CHANGED 事件細緻化 | 未寫 | 未開始 |
| 005 | 觀測性完善：Grafana dashboard、Alertmanager 規則、分散式追蹤 | `openspec/changes/add-platform-observability`、`add-business-metrics-and-alerting`、`add-distributed-tracing`（皆待歸檔） | **三個 change 皆已完成並上線**（2026-07-28/29）。`add-platform-observability`：ServiceMonitor 雖然 001/002/003 就隨服務建了，但 Service 一直缺 `metadata.labels`，Prometheus 實際上從未採集到——修好，同時補上 kafka-exporter、postgres_exporter（含 Path A 業務指標）、Longhorn/ingress-nginx/ArgoCD/cert-manager 的 ServiceMonitor；host node-exporter 已寫好 playbook，待手動跑（需 sudo，見待決事項）。`add-business-metrics-and-alerting`：Path B 埋點（collector/worker）、SLO-1/SLO-2、7 條 job-radar 告警 + 3 條平台告警（皆有 promtool 單元測試，過程中抓到 3 條告警因 PromQL label 不匹配而恆為空的實作 bug）、AlertmanagerConfig CRD 路由、pipeline dashboard，CI 全程只觸發一次。**副產品**：過程中發現 `job-radar-discord` webhook 從未真的設定過（見上方前置作業修正）、Prometheus 自己的 Longhorn volume 有個 23 天未清的 snapshot 佔用超過邏輯容量（見 `homelab-infra/TROUBLESHOOTING.md`）。`add-distributed-tracing`：一開始因為叢集 CPU request 帳面超賣（GitLab chart 預設值過高）排不進去，把 GitLab 的 CPU request 調降 + worker1 加 vCPU 後才有 headroom，見 `homelab-infra/ARCHITECTURE.md`「GitLab CPU 調整、worker1 加 vCPU、裝 Tempo」章節；Java 端埋點與端到端驗證見下方可觀測性章節 |
| 006 | 新增 104 為第三個來源（`needsDetail=true`，Cloudflare 節流見 D19）；同時導入新的排程模型（三來源並行、淺掃深掃合併邏輯，見 D6）與掃描結果回報（見 D21），三者一起上線 | `docs/source-api-notes.md`（104 章節）；openspec change 未開 | **規格與架構設計完成，未實作**。List/Detail API、Area/JobCat 代碼表、欄位對照、語言選型（Java，見 D22）、排程模型、錯誤處理與告警（D19/D20）、掃描回報機制（D21）皆已確認並記錄；`order`/更新時間篩選/`jobType` 列舉仍有未確認項（見待決事項），不擋路。實作時須同時處理共用常數抽離（D23） |
| 007+ | LLM extraction 插槽（Workday / Threads）、跨平台去重、transactional outbox、對外公開網址（Cloudflare Tunnel） | 未寫 | 未開始 |

## 待決事項

- [x] Yourator 實際 API 形狀調查——已完成，見 `docs/source-api-notes.md`
- [x] k8s repo 內版型：確認現有 root app（`argocd-root-app.yml`，`directory.recurse: true`）
      會遞迴同步純 YAML manifest，不需要 kustomize 或逐 app 建 ArgoCD Application（見 001 design.md）
- [x] Kafka 部署方式：純 StatefulSet + KRaft（未用 Strimzi operator，對單 broker 過重），已上線運作
- [x] Java package 前綴與 groupId 命名：`dev.jobradar`（`dev.jobradar.common` / `.collector` /
      `.worker` / `.api`）

**新發現、還沒處理的：**

- [x] ~~`k8s-worker2` 這個節點還沒信任內部 registry 的 CA~~ **2026-07-29 已解決**：
      worker1 加 vCPU 那次重開機風暴期間，frontend pod 真的被排到 worker2 上，
      當場 `ImagePullBackOff`（`x509: certificate signed by unknown authority`）
      印證了這裡的預測。已補跑 `install-registry-ca-trust.yml`，三個節點都確認
      有信任這張 CA（見 `homelab-infra/TROUBLESHOOTING.md`）
- [ ] Yourator 的 `sort` 參數除了預設 `most_related`，是否還有 `latest`/`created_at` 這類可以拿來
      做時間游標排序的合法值，還沒實測驗證（見 `docs/source-api-notes.md`），如果有，可能可以
      取代現在土炮的固定翻頁策略
- [ ] 對外公開網址：CGNAT 環境下確認走 Cloudflare Tunnel（免費、不用開 port、不暴露家用 IP）+
      便宜網域，方向已討論定案，還沒實作
- [ ] **`job-radar-discord` webhook 需要真的設定**：目前是 `secrets.example.yaml`
      的 `"REPLACE_ME"` placeholder，Discord 推播從 2026-07-22 左右就一直失敗，
      DLQ 已累積 200+ 筆（見 `homelab-infra/TROUBLESHOOTING.md`「job-radar-dlq」）。
      建立真的 webhook 後重新 seal `apps/job-radar/discord-sealed-secret.yaml`
- [ ] host node-exporter 需要手動跑一次 `ansible-playbook`（需要互動式 sudo，
      見 `add-platform-observability/design.md` 附錄的指令），跑完才能驗證 TLP
      對實體 CPU 頻率/溫度的實際效果
- [ ] Longhorn 上 Prometheus 自己的 volume 有個 23 天未清的舊 snapshot，佔用
      超過邏輯容量（112.6%），是否清除待自行決定（見
      `homelab-infra/TROUBLESHOOTING.md`「Storage（Longhorn）」）
- [x] ~~`add-distributed-tracing` 尚未開始~~ **2026-07-29 已完成並上線**，見下方
      Roadmap Phase 005 與可觀測性章節的 Tracing 小節
- [ ] 104 `order`/`asc` 完整代碼表未知，尤其「最近更新」對應數值——D6 時間游標策略在
      104 上暫時無法套用，先用固定翻頁策略頂著（見 `docs/source-api-notes.md`）
- [ ] 104「更新時間」篩選（本日最新/三日內/一週內等）對應的實際 query 參數名稱未知
- [ ] 104 `jobType` 數字列舉完整對照未知（只有 1 個樣本點）
- [ ] 104 cf_clearance/__cf_bm 沒有自動刷新機制，需要定義「人工重新貼 cookie」的
      實際操作流程（誰來做、多久檢查一次、告警觸發後的 SOP），目前只有「該不重試、
      該告警」的原則（見 D19），流程本身還沒寫
- [ ] 共用掃描常數抽離（見 D23）尚未動手，104 實作時要一併處理，不能繼續複製貼上
      `MAX_RETRY`/`MAX_SCAN_DURATION`/退避公式
- [ ] 掃描結果回報 Discord（見 D21）是全新元件，尚未實作；「新增筆數」用的簡化版
      時間窗查詢邏輯需要在真的接上 104 排程後驗證準確度
- [x] ~~104 collector 要用 Java 還是 Python~~ **已決定：Java**（見 D22），
      `104-api-poc` 已用純 Java `HttpClient` 實測驗證可行，不需要引入 Python
- [x] ~~前端（React Admin）完全還沒討論過~~ **已設計（見 D24）**：104 套進既有的
      `useSourceFacets`/`FacetsService` 機制，需要新增 `104FacetsClient`、`SOURCE_CHOICES`
      加一筆、`CategoryAndLocationInputs` 多一個分支、型別更新——尚未實作
