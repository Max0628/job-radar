# Design: add-walking-skeleton

上位文件：`docs/architecture.md`（系統圖、決策 D1–D12、資料模型、topic 合約）。
本文件只記錄骨架階段的實作層決定，不重複架構內容。

## 模組與管線對應

| 模組 | 內容 | 部署 |
|------|------|------|
| common | envelope record（schemaVersion/type/source/sourceJobId/scrapedAt/url/payload）、domain model、Kafka topic 常數 | 不可執行 |
| collector | `@Scheduled` 觸發（每 2h + jitter）→ 讀 `search_queries` → Yourator list adapter 翻頁 → 每筆發 `jobs.discovered`；游標讀寫 `scrape_cursors`；每輪寫 `scrape_runs` | boot jar / pod |
| worker | fetcher：消費 discovered → 查 `jobs` 決定抓/不抓 → 限速（見下方實作偏離記錄）→ 發 `jobs.raw`。normalizer：消費 raw → `raw_documents` + `jobs` upsert + `job_snapshots` insert-ignore → 新缺發 `jobs.events`(NEW)。notifier：消費 events → Discord webhook embed | boot jar / pod |
| api | 骨架階段僅 actuator health + Prometheus endpoint | boot jar / pod |

## 實作層決定

- **Kafka 部署**：純 StatefulSet + KRaft 單 broker，不用 Strimzi（單 broker 上 operator 過重；
  architecture.md 待決事項在此定案）
- **錯誤處理**：spring-kafka `DefaultErrorHandler`，重試 3 次（指數退避）後 `DeadLetterPublishingRecoverer`
  進 `<topic>.dlq`
- **冪等驗收的實作依據**：`jobs` 表 `(source, source_job_id)` unique constraint 是唯一真相，
  NEW 事件只在 upsert 實際 INSERT 時發（用 `INSERT ... ON CONFLICT ... RETURNING (xmax = 0)` 判斷）
- **Discord**：官方 webhook API，embed 含職稱、公司、薪資（缺則略）、連結；webhook URL 由
  SealedSecret 注入環境變數
- **本機開發環境**：docker-compose（Kafka + PG），與部署目標無關，只為開發迴圈
- **測試策略**：normalizer 冪等與 diff 邏輯用 Testcontainers（PG）整合測試；scraper adapter
  用錄下來的真實回應 fixture 做單元測試；Kafka 流程以 EmbeddedKafka 或 Testcontainers 擇一
- **k8s manifests**：純 YAML，不用 kustomize。查了 homelab-infra 現有的
  `argocd-root-app.yml`（`path: .`、`directory.recurse: true`）——這是已經在跑的 root app，
  會遞迴找 k8s repo 底下所有 manifest 直接 apply，不是逐 app 建 ArgoCD Application、也不會對
  子目錄自動跑 kustomize build。所以 `apps/job-radar/*.yaml` 用純 manifest 放好、push 上去，
  root app 會自動同步，不用額外註冊資源。之後如果 app 數量變多、需要各自獨立的 sync 策略，
  再考慮 app-of-apps。

## 實作偏離記錄

- **envelope 拿掉 JSON 內的 `type` 欄位**：architecture.md 的 envelope 範例含 `"type": "discovered|raw|event"`，
  但 Kafka topic 本身已經是 discovered/raw/event 的判別依據（每個 topic 只承載一種訊息），payload
  內再放一個 type 欄位是多餘的判別欄位。改用三個對應各 topic 的具體 record 類型
  （`DiscoveredEnvelope`/`RawEnvelope`/`JobEventEnvelope`），也讓 Spring Kafka 的
  `JsonDeserializer` 不用處理泛型型別擦除問題。`schemaVersion` 欄位保留。
- **Detail fetcher 的間隔限制改用明確 synchronized gate，不單靠 Resilience4j RateLimiter**：
  實測（對 130 筆真實 Yourator 職缺）Resilience4j 的 `@RateLimiter(limit-for-period=1, limit-refresh-period=1s)`
  在 Kafka listener 的併發環境下實際吞吐約 1.6 req/s，超過 architecture.md 要求的「間隔 ≥1s」。
  改為 `YouratorDetailScraper` 內用一個 `synchronized` 區塊記錄上次請求時間、必要時 `Thread.sleep`
  補足間隔，這樣不管上層併發模型如何都有確定性保證。Resilience4j 保留 `@Retry` 專職處理 429 退避
  （兩個關注點本來就不同：一個是「主動不要超速」，一個是「被拒絕後怎麼重試」）。
- **`search_queries` 的頻率欄位用 `intervalMinutes`，不是 architecture.md 寫的「每 2-4h」固定值**：
  做成可設定值，Yourator 目前先固定填 120 分鐘，其他來源加入時各自設定，不用改 schema。

## 附錄：Yourator API 調查結果

調查方式：直接對 `www.yourator.co` 發 request 觀察真實回應（無需登入、無需認證）。

### List / Search API

```
GET https://www.yourator.co/api/v4/jobs/?keyword={keyword}&page={page}
Accept: application/json
```

回應（`payload` 底下）：

```json
{
  "hasMore": true,
  "currentPage": 1,
  "nextPage": 2,
  "jobs": [{
    "id": 41246,
    "name": "Lead Software Engineer",
    "path": "/companies/aifian/jobs/41246",
    "salary": "NT$ 1,800,000 - 2,500,000 (年薪)",
    "lastActiveAt": "一天內更新",
    "location": "臺北市",
    "companyId": 295,
    "tags": [],
    "company": { "id": 295, "path": "/companies/aifian", "brand": "AIFIAN", "enName": "aifian", "logo": "...", "badges": ["verified"] },
    "thirdPartyUrl": null,
    "externalSource": null
  }]
}
```

- `id` 是 `sourceJobId`；detail URL = `https://www.yourator.co` + `path`
- 每頁固定 20 筆；`hasMore`/`nextPage` 驅動分頁
- **`salary` 是自由文字**（"NT$ 1,800,000 - 2,500,000 (年薪)"、"面議（經常性薪資達4萬元）" 等格式不一），不可靠，只作為 list 階段的粗略顯示；正式 `salary_min/max` 以 detail 頁的結構化資料為準
- **`lastActiveAt` 只是粗顆粒度的相對時間字串**（如「一天內更新」），不是精確時間戳，無法拿來做精確排序游標

### 對 D6（時間游標 + early termination）的關鍵發現

**沒有 `sort` 參數，且觀察到預設順序不是依時間或 id 單調排列**——同一頁混雜 id 41246、46192、4159、4160（新舊交錯），推斷是相關性排序（可能綜合 verified badge、贊助狀態等因素）演算法，而非 chronological。

這代表「翻頁翻到全是舊資料就停」的 early-termination 對 Yourator **不安全**：新職缺可能不在第一頁，靠 id 或時間游標提早結束會漏掉。

**Yourator adapter 的實際策略（D6 的 per-source 落地，不是推翻 D6）：**
- 放棄游標式 early termination，改為**每輪固定掃描前 N 頁**（設定值，預設 10 頁 = 每關鍵字 200 筆），N 可調
- 冪等 upsert 本來就會處理重複——多掃到的舊職缺對 DB 只是免費更新 `last_seen_at`，成本是多打幾個 list request（一輪一關鍵字最多 10 次），量級上完全可接受（見 architecture.md 的 request 預算估算）
- `scrape_cursors` 表在 Yourator adapter 上退化成「記錄本輪掃了幾頁、下次從第 1 頁重新開始」，不是真正的時間游標；其他來源（如 104，若有精確 `updated_at`）可以用真正的游標，這正是「per-source adapter 邏輯，不做全域規則」的體現
- 深掃/淺掃的區別對 Yourator 因此意義不大（兩者都是固定翻 N 頁的全量式掃描），差異只在 N 的大小——這點留給 002（多來源）時再視 104 的實際行為決定是否要讓雙節奏更有意義

### Detail 頁

```
GET https://www.yourator.co{path}    (例如 /companies/aifian/jobs/41246)
```

伺服端渲染的一般 HTML（非 SPA JSON），但頁面內嵌乾淨的 **schema.org JobPosting JSON-LD**（`<script type="application/ld+json">`），這是 detail 資料的主要來源，不用 parse HTML DOM：

```json
{
  "@context": "https://schema.org/",
  "@type": "JobPosting",
  "title": "Lead Software Engineer",
  "description": "<h2>...</h2>...(HTML 格式的 JD 全文)",
  "identifier": { "@type": "PropertyValue", "name": "諦諾智金股份有限公司", "value": 41246 },
  "datePosted": "2026-07-18 02:00:09 +0800",
  "validThrough": "2112-09-03",
  "employmentType": "FULL_TIME",
  "hiringOrganization": { "@type": "Organization", "name": "AIFIAN", "sameAs": "https://www.aifian.com/", "logo": "..." },
  "jobLocation": { "@type": "Place", "address": { "streetAddress": "...", "addressLocality": "臺北市", "addressRegion": "臺北市", "addressCountry": "TW" } },
  "baseSalary": { "@type": "MonetaryAmount", "currency": "TWD", "value": { "unitText": "YEAR", "minValue": 1800000, "maxValue": 2500000, "value": 2150000 } }
}
```

- `baseSalary` 是**結構化**的（有 minValue/maxValue/currency/unitText）——detail 頁的薪資取這裡，不用 list 的自由文字
- 沒有 `dateModified` 欄位，只有 `datePosted`；因此 detail 層級的「JD 是否變更」判斷只能靠 D5 既定的 `content_hash`（對 description/salary/title 算 hash 比對），不能靠平台給的時間戳
- `description` 是帶 HTML tag 的字串，正規化時需視需求 strip 或保留

### 其他觀察

- 無需登入、無 API key、無明顯 rate limit（調查期間 ~8 次請求皆 200，Cloudflare 在前但未觸發挑戰）；仍照 architecture.md 的限速規則走（同來源並發 ≤2、間隔 ≥1s、429 退避）
- `robots.txt` 只禁止 `/r/*`（追蹤用的轉址連結），`/api/` 與 `/jobs`、`/companies/*/jobs/*` 皆未禁止
- User-Agent 需帶正常瀏覽器字串（空 UA 或明顯爬蟲 UA 未實測，保守起見一律帶）
