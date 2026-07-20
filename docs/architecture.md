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

圖例：單線框 `┌─┐` = 服務/process；雙線框 `╔═╗` = Kafka topic。三個來源框下方標的是
`docs/source-api-notes.md` 目前的驗證現況。

```
    外部求職平台（collector 主動呼叫，遵守禮貌爬蟲：同來源並發≤2、間隔≥1s、429退避）

    ┌────────────────────────┐     ┌────────────────────────┐     ┌────────────────────────┐
    │        Yourator        │     │          104           │     │       CakeResume       │
    └────────────────────────┘     └────────────────────────┘     └────────────────────────┘
        term[]/area[]/sort               Cloudflare 擋，暫緩                  api.cake.me
               已驗證可用                         無公開API                          已驗證可用
                 │                              │                              │
                 ┴──────────────────────────────┬──────────────────────────────┴
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
                                                                         │     (roadmap)      │
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
> JSON-LD；但 CakeResume 的 search API 回應已經含完整職缺全文，可能不需要 detail 這一步
> （待確認）。細節與各來源實測結果見 `openspec/changes/add-walking-skeleton/design.md` 附錄、
> `docs/source-api-notes.md`。

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

## Repo 結構（Gradle multi-module）

```
job-radar/
├── CLAUDE.md            # 給執行 session 的守則
├── specs/               # SDD 文件（本檔 + feature specs）
├── settings.gradle
├── common/              # 訊息 envelope、domain model、共用設定（不可執行）
├── collector/           # Scheduler + 各平台 list scraper adapter（boot jar）
├── worker/              # detail-fetcher / normalizer / notifier 三個 Kafka consumer（boot jar）
├── api/                 # REST API（boot jar）；未來 frontend/ 也放本 repo
└── .gitlab-ci.yml       # build → test → build image → push registry → 更新 k8s repo image tag
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
  "source": "yourator | 104 | ...",
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

- Manifests 放既有的 `k8s` repo（ArgoCD root app 已指向它），建議結構：`apps/job-radar/`（collector / worker / api Deployment + Kafka、PostgreSQL StatefulSet + SealedSecrets），用 kustomize 收攏
- CI 流程：push → GitLab Runner 跑 test → build image → push registry → 以 commit SHA 更新 k8s repo 的 image tag → ArgoCD 自動 sync
- 資源預算：T480 共 4C/8T；所有 JVM 設好 `-Xmx`（各 512MB 內）與 k8s requests/limits；Kafka heap 1GB 內

## 可觀測性

- Metrics：Micrometer + Prometheus endpoint，接既有 kube-prometheus-stack（ServiceMonitor）。關鍵指標：每來源爬取成功率、每輪新缺數、consumer lag、DLQ 深度、Discord 推播成功率
- Logs：結構化 JSON logs → 既有 Promtail/Loki
- Alerts（Alertmanager）：DLQ > 0、單一來源連續 3 輪爬取失敗、consumer lag 持續增長

## 前置作業（在 homelab-infra 側，寫程式前必須完成）

1. **CA 信任缺口（必修）**：`install-ca.yml` 目前只發 CA 給 iPhone/Mac。需新增 playbook 將 homelab Root CA 裝進三台 k8s node 的系統信任庫（`/usr/local/share/ca-certificates/` → `update-ca-certificates` → 重啟 containerd），否則節點拉不了 registry image、Runner 也 push 不了
2. cluster 安裝 Sealed Secrets controller（helm，納入 homelab-infra 管理）
3. GitLab 上建立 `job-radar` project，確認 Runner 可用、Registry 可 push
4. 建立 Discord server + webhook，URL 以 SealedSecret 管理

## Roadmap

| Phase | 內容 | Spec |
|-------|------|------|
| 001 | Walking skeleton：Yourator 單一關鍵字走通全管線到 Discord，部署進 cluster | `specs/001-walking-skeleton.md` |
| 002 | 104 來源 adapter；深掃節奏；search_queries 多關鍵字 | 未寫 |
| 003 | REST API + 簡易前端看板 | 未寫 |
| 004 | 職缺消失偵測（closed sweep）+ CHANGED 事件細緻化 | 未寫 |
| 005 | 觀測性完善：Grafana dashboard、Alertmanager 規則 | 未寫（可與 002–003 並行） |
| 006+ | LLM extraction 插槽（Workday / CakeResume / Threads）、跨平台去重、transactional outbox | 未寫 |

## 待決事項

- [ ] Yourator / 104 實際 API 形狀調查（endpoint、分頁、排序參數、欄位）——001 的第一個 task，調查結果寫回 spec
- [x] k8s repo 內版型：確認現有 root app（`argocd-root-app.yml`，`directory.recurse: true`）
      會遞迴同步純 YAML manifest，不需要 kustomize 或逐 app 建 ArgoCD Application（見 001 design.md）
- [ ] Kafka 部署方式：Strimzi operator vs 純 StatefulSet（傾向純 StatefulSet + KRaft，operator 對單 broker 過重，001 時定案）
- [ ] Java package 前綴與 groupId 命名
