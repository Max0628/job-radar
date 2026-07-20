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

> **2026-07-20 更正**：本節原本記錄的 `keyword` 參數是錯的——用瀏覽器 DevTools 側錄前端真實送出的
> request 後發現，正確參數是 `term[]`（陣列），`keyword` 對這個 API 完全無效（送什麼值都回同一批
> 結果，之前誤以為「關鍵字比對很鬆散」，其實是關鍵字根本沒被套用）。同一次側錄也發現有 `area[]`
> 和 `sort` 參數，一併更正如下；下面「對 D6 的關鍵發現」一節的「沒有 sort 參數」結論也已證實錯誤，
> 見更正說明。

### List / Search API

```
GET https://www.yourator.co/api/v4/jobs?page={page}&sort={sort}&term[]={keyword}&area[]={areaCode}
Accept: application/json
```

- `term[]`：關鍵字，陣列格式，中英文皆可（已實測 `term[]=後端工程師` 回傳的 20 筆結果標題皆相關）
- `area[]`：地區代碼，陣列格式，值來自 `GET /api/v4/areas?query_key[]=job_areas`（例：
  `TPE`=臺北市、`NWT`=新北市、`TAO`=桃園市、`TXG`=臺中市、`TNN`=臺南市、`KHH`=高雄市；已實測
  `area[]=TPE` 搭配 `term[]` 使用，20 筆結果 location 全部是「臺北市」）
- `sort`：已確認存在（前端預設送 `most_related`），除此之外還有哪些合法值未驗證
- 皆不需要 cookie / CSRF token / 登入，plain request 就能拿到正確過濾的結果（側錄到的瀏覽器請求
  帶了 `cf_clearance`、`_yourator_session` 等 cookie 與 `x-csrf-token` header，但拿掉這些純
  server-to-server 呼叫一樣回 200 且結果正確，代表這些是瀏覽器環境附帶的東西，不是這個端點的必要條件）

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
    "externalSource": null,
    "follow": false
  }],
  "recommendedJobs": [ /* 結構未展開查看 */ ],
  "trendingKeywords": [ /* 結構未展開查看 */ ]
}
```

- `id` 是 `sourceJobId`；detail URL = `https://www.yourator.co` + `path`
- 每頁固定 20 筆；`hasMore`/`nextPage` 驅動分頁
- **`salary` 是自由文字**（"NT$ 1,800,000 - 2,500,000 (年薪)"、"面議（經常性薪資達4萬元）" 等格式不一），不可靠，只作為 list 階段的粗略顯示；正式 `salary_min/max` 以 detail 頁的結構化資料為準
- **`lastActiveAt` 只是粗顆粒度的相對時間字串**（如「一天內更新」），不是精確時間戳，無法拿來做精確排序游標
- `follow`、`recommendedJobs`、`trendingKeywords` 是這次重新驗證才發現、先前未記錄的欄位，用途未深究

### 對 D6（時間游標 + early termination）的關鍵發現 —— 待重新驗證

> **此節原始結論建立在「沒有 sort 參數」這個已證實錯誤的前提上，需要重新驗證才能定案，
> 下面保留原始推論內容供對照，並標註哪些部分因此站不住腳。**

原始推論：「同一頁混雜 id 41246、46192、4159、4160（新舊交錯），推斷是相關性排序演算法，
而非 chronological」——**但這個觀察本身是用錯誤的 `keyword` 參數測出來的**，`keyword` 不生效
代表當時看到的順序其實是「無關鍵字篩選」下的預設排序（很可能等同於前端預設的
`sort=most_related`），不能排除換成別的 `sort` 值（如果存在類似 `latest`/`created_at` 的選項）
會有 chronological 順序、讓 early-termination 重新變得安全。

**這代表 D6 的 per-source 落地策略（放棄游標式 early termination、改為固定掃描前 N 頁）目前
仍先維持不變**（沒有證據顯示可以安全改用游標式提前終止），但理由要修正為「`sort` 除了
`most_related` 還有哪些值、是否有能拿來當時間游標的選項，尚未驗證」，而不是原本寫的
「沒有 sort 參數」。**下一步待辦**：列舉 `sort` 的合法值（可能要側錄下拉選單操作，或用送錯值
讓 API 的驗證錯誤訊息透露合法選項，仿照 CakeResume 那次的做法），確認後才能決定要不要改採
游標式策略。

**Yourator adapter 目前維持的策略（未變動，僅前提更正）：**
- 維持固定掃描前 N 頁（設定值，預設 10 頁 = 每關鍵字 200 筆），N 可調
- 冪等 upsert 本來就會處理重複——多掃到的舊職缺對 DB 只是免費更新 `last_seen_at`，成本是多打幾個 list request（一輪一關鍵字最多 10 次），量級上完全可接受（見 architecture.md 的 request 預算估算）
- `scrape_cursors` 表在 Yourator adapter 上退化成「記錄本輪掃了幾頁、下次從第 1 頁重新開始」，不是真正的時間游標；其他來源（如 104，若有精確 `updated_at`）可以用真正的游標，這正是「per-source adapter 邏輯，不做全域規則」的體現
- 深掃/淺掃的區別對 Yourator 因此意義不大（兩者都是固定翻 N 頁的全量式掃描），差異只在 N 的大小——這點留給 002（多來源）時再視 104 的實際行為決定是否要讓雙節奏更有意義

### Detail 頁

```
GET https://www.yourator.co{path}    (例如 /companies/aifian/jobs/41246)
```

**這不是第二支 API，`path` 也不是 API 參數。** `path` 是 list API 回應裡帶的一般網址路徑（前端
拿它組「查看詳情」連結給人點的），detail 端點打的就是一般人瀏覽器打開會看到的職缺頁面本身。
用瀏覽器渲染這個網址、側錄背後所有請求後，**沒有找到任何 `jobs/{id}` 這類 detail JSON API**，
只有語系字串包和追蹤/動態區塊呼叫——代表 detail 頁是伺服端直接渲染好的，職缺全文在第一次
拿到的 HTML 裡就有了，不需要瀏覽器執行 JS 再打第二次請求，一般的 HTTP client GET 就夠。

伺服端渲染的一般 HTML（非 SPA JSON），但頁面內嵌乾淨的 **schema.org JobPosting JSON-LD**（`<script type="application/ld+json">`），這是 detail 資料的主要來源。**實作上不是「parse HTML」**：
只需要用 CSS selector 選出這一個節點（Java 可用 Jsoup 的 `doc.selectFirst("script[type=application/ld+json]")`），不用管周圍排版的 DOM 結構或 class 名稱，網站改版也不影響，因為這段是
給 Google 搜尋結果讀的標準格式（SEO 用途），非特地留給爬蟲：

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

## 附錄：CakeResume API 調查結果

調查方式：同 Yourator，直接對 `api.cake.me` 發 request 觀察真實回應（無需登入、無需認證）；
端點本身是用「故意送錯、讀 422 錯誤訊息反推正確欄位」的方式試出來的（見下方 request 格式）。

> 網域已改為 `cake.me`，`www.cakeresume.com` 會 301 導向過去。

### List / Search API

```
POST https://api.cake.me/api/client/v1/jobs/search
Content-Type: application/json

{
  "query": "後端工程師",
  "filters": { "location": "Taipei" },
  "page": 1,
  "sort_by": "latest"
}
```

- **必須 POST + JSON body**，GET + query string 會被路由誤判成查單一職缺，回 404
  `{"msg":"The job is not found"}`
- `filters.location` 已驗證有效（自由文字如 `"Taipei"`，非地區代碼）
- `sort_by` 必填，只接受 `"popularity"` 或 `"latest"`（relevance 排序僅供有下廣告的 campaign 用）
- 已逐一實測有效的 `filters` 欄位（用基準查詢 `total_entries` 變化量驗證）：`location`、
  `professions`、`job_types`、`seniority_levels`、`remote`、`year_of_seniority`、
  `number_of_management`、`lang_names`（陣列型別，值域見回應的 `available_facets`）
- 實測**無效**（直接塞會被忽略）：`tech_labels`、`sectors`——這兩個在 `available_facets` 回應裡
  包在 `page` 子物件下，推測屬於「公司搜尋」而非「職缺搜尋」的篩選維度，正確路徑未知
- `salary`（物件型別）、`inclusivity_traits` 尚未測試

### Detail 資料：不需要獨立 detail 階段（初步判斷，待確認）

**跟 Yourator 的關鍵差異**：search API 回應的每筆職缺物件已經含完整 `description`（實測 2155
字元，結尾是完整句子，非截斷），另外還有 `salary`、`seniority_level`、`job_type`、
`min_work_exp_year`、`content_updated_at`、公司資訊（`page`）。這代表 CakeResume 的 adapter
可能**不需要 worker 的 detail fetcher 這一段**——normalizer 可以直接消費 search 階段拿到的資料。

**尚未驗證**：這是否真的等於完整頁面內容（還沒找一個真實 CakeResume 職缺頁面逐欄比對過），
先記錄現況，實作 adapter 前建議先做這個比對再定案要不要跳過 detail 階段。

### 對架構的影響：兩段式爬蟲是 per-source 決定，不是全域規則

architecture.md 的 D3（兩段式爬蟲：list scraper 發現 + detail fetcher 抓全文）預設所有來源都
需要兩段式，但實測下來：

| 來源 | List 階段資料完整度 | 是否需要 detail 階段 |
|---|---|---|
| Yourator | 只有摘要（無 description），detail 是另一個 HTML 頁面 | 需要（兩段式） |
| CakeResume | search 回應已含完整 description 等欄位 | 可能不需要（一段式，待確認） |

這不是推翻 D3，D3 的「list 貴/detail 便宜、限速集中在 fetcher」道理對 Yourator 仍然成立；只是
per-source adapter 要能表達「這個來源的 fetcher 階段其實是 no-op、直接把 discovered payload
轉成 raw」，而不是預設每個來源都要真的對外發第二次 HTTP request。實作時 worker 的 fetcher
consumer 可能要能識別「這筆 discovered 訊息的來源本身已經帶了完整 payload」並跳過實際抓取，
細節留給 002（多來源）落地時定案。
