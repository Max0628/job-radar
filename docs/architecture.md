# job-radar 架構藍圖

> 個人用職缺聚合工具：自動爬取各求職平台、正規化後聚合進資料庫，
> 新職缺透過 Discord 推播，並提供 API / 前端查詢。
> 部署於個人 homelab K8s（見 `~/projects/homelab-infra/ARCHITECTURE.md`），
> 同時作為 SRE / Infra 方向的面試作品集。
>
> 本文件是所有 spec 的最上位文件。**已決策事項不要重新討論**；
> 執行時若發現決策行不通，先回報並更新本文件，再動程式碼。

## 目標與非目標

**目標**
- 不用再手動刷 104 / Yourator 等平台，新職缺主動推到 Discord
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
`docs/source-api-notes.md` 目前的驗證現況（104 暫緩中，不在圖上，見下方說明）。

```
    外部求職平台（collector 主動呼叫，遵守禮貌爬蟲：同來源並發≤2、間隔≥1s、429退避）

    ┌────────────────────────┐     ┌────────────────────────┐
    │        Yourator        │     │       CakeResume       │
    └────────────────────────┘     └────────────────────────┘
        term[]/area[]/sort                  api.cake.me
        已驗證可用，正式運作              已驗證可用，正式運作
                 │                              │
                 ┴──────────────────────────────┴
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
> （`needsDetail=false`，見 `CakeResumeListScraper`）。104 評估過（見 `docs/source-api-notes.md`），
> 整個網域掛 Cloudflare Turnstile、無公開查詢 API，plain HTTP request 全部回 403，**暫緩、不是
> 放棄**——現階段先以 CakeResume 作為第二來源，104 之後仍打算做，屆時要評估瀏覽器自動化繞過
> Cloudflare 的方式與成本。細節與各來源實測結果見 `openspec/changes/add-walking-skeleton/design.md`
> 附錄、`docs/source-api-notes.md`。

## 決策記錄（含被否決的選項）

| # | 決策 | 理由 | 被否決的選項 |
|---|------|------|--------------|
| D1 | Java 21 + Spring Boot 3.x，virtual threads，blocking style | 使用者主力語言；workload 是低量 IO-bound，效能非選型軸；生態成熟（spring-kafka、Resilience4j、@Scheduled） | Go（SRE 訊號改由平台層提供，避免三線作戰）；WebFlux（複雜度稅，明確禁用） |
| D2 | Kafka 單 broker（KRaft mode）做服務解耦 | 使用者要練 Kafka 且當面試素材；topic/consumer group 對應解耦模型 | NATS JetStream（更輕但學習價值較低）；PG-based queue（耦合） |
| D3 | 兩段式爬蟲：list scraper（發現）＋ detail fetcher（抓全文）；**是否真的需要 detail 這一段是 per-source 決定**（Yourator 需要；CakeResume 的 list 已含全文，可能不需要，見 design.md 附錄） | detail 貴、list 便宜；限速集中在 fetcher；單筆重試；平台改版只修薄薄的 scraper | 單段式每輪全抓 detail（request 爆量）；scraper 直連 DB（職責過厚） |
| D4 | PostgreSQL 單庫：核心欄位正規化 + JSONB 放平台專屬欄位；`job_snapshots` append-only；raw payload 也落 PG | 職缺量級（全台十萬級）不需要 NoSQL；SQL 查詢/索引/交易保留；raw 可重放（改 parser / 之後上 LLM 不用重爬） | MongoDB（多養一套系統無收益）；raw 落檔案系統（k8s 內不如落 PG 方便） |
| D5 | Worker 全部冪等：jobs 以 `(source, source_job_id)` unique upsert；快照 insert-ignore；diff 事件以 DB 狀態為準 | Kafka at-least-once，訊息必然重複；冪等做對則任意重試/重放皆安全 | 以「收到訊息」判斷新缺（會重複推播） |
| D6 | 排程雙節奏：淺掃（增量、時間游標 + early termination、每 2–4h + jitter）＋ 深掃（每日凌晨全量） | 新缺浮在「按更新時間排序」的前面；穩定狀態每輪 1–2 頁 list 即可；深掃補漏並更新 last_seen_at | 每輪全量（浪費）；固定翻 N 頁（保底手段，非主策略） |
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
  "source": "yourator | cakeresume | 104（暫緩）",
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
- `scrape_runs`：每輪執行記錄（source、query、起訖、抓到幾筆、成功/失敗）——同時是監控素材與未來 closed sweep 的依據
- `search_queries`：設定表（source × 關鍵字 × 頻率），scheduler 據此觸發
- `scrape_cursors`：每個 query 的上次掃描時間游標（collector 唯一可寫的表）

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
| 002 | 多來源 adapter；search_queries 多關鍵字 | `openspec/changes/add-multi-source-cakeresume`（待歸檔） | **已完成並上線**——CakeResume 作為第二來源已上線。104 因 Cloudflare Turnstile 全站防護、無公開查詢 API 暫緩（見 `docs/source-api-notes.md`），**不是放棄**，之後仍要做，需另外評估繞過 Cloudflare 的方式 |
| 003 | REST API + 前端看板 | `openspec/changes/add-job-dashboard`（待歸檔） | **已完成並上線**：`api` 唯讀查詢端點、React Admin 前端（職缺瀏覽、search_queries 配置台、收藏），部署見 D13 |
| 004 | 職缺消失偵測（closed sweep）+ CHANGED 事件細緻化 | 未寫 | 未開始 |
| 005 | 觀測性完善：Grafana dashboard、Alertmanager 規則、分散式追蹤 | `openspec/changes/add-platform-observability`、`add-business-metrics-and-alerting`、`add-distributed-tracing`（皆待歸檔） | **三個 change 皆已完成並上線**（2026-07-28/29）。`add-platform-observability`：ServiceMonitor 雖然 001/002/003 就隨服務建了，但 Service 一直缺 `metadata.labels`，Prometheus 實際上從未採集到——修好，同時補上 kafka-exporter、postgres_exporter（含 Path A 業務指標）、Longhorn/ingress-nginx/ArgoCD/cert-manager 的 ServiceMonitor；host node-exporter 已寫好 playbook，待手動跑（需 sudo，見待決事項）。`add-business-metrics-and-alerting`：Path B 埋點（collector/worker）、SLO-1/SLO-2、7 條 job-radar 告警 + 3 條平台告警（皆有 promtool 單元測試，過程中抓到 3 條告警因 PromQL label 不匹配而恆為空的實作 bug）、AlertmanagerConfig CRD 路由、pipeline dashboard，CI 全程只觸發一次。**副產品**：過程中發現 `job-radar-discord` webhook 從未真的設定過（見上方前置作業修正）、Prometheus 自己的 Longhorn volume 有個 23 天未清的 snapshot 佔用超過邏輯容量（見 `homelab-infra/TROUBLESHOOTING.md`）。`add-distributed-tracing`：一開始因為叢集 CPU request 帳面超賣（GitLab chart 預設值過高）排不進去，把 GitLab 的 CPU request 調降 + worker1 加 vCPU 後才有 headroom，見 `homelab-infra/ARCHITECTURE.md`「GitLab CPU 調整、worker1 加 vCPU、裝 Tempo」章節；Java 端埋點與端到端驗證見下方可觀測性章節 |
| 006+ | LLM extraction 插槽（Workday / Threads）、跨平台去重、transactional outbox、對外公開網址（Cloudflare Tunnel） | 未寫 | 未開始 |

## 待決事項

- [x] Yourator 實際 API 形狀調查——已完成，見 `docs/source-api-notes.md`
- [x] 104 實際 API 形狀調查——已完成（Cloudflare Turnstile 全站防護，暫緩），見
      `docs/source-api-notes.md`
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
