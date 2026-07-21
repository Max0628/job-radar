# Design: add-job-dashboard

## Context

**現狀**：`api` 模組目前只有 actuator health + Prometheus endpoint（Phase 001 骨架）。
`jobs`／`search_queries` 表都有資料，但沒有任何查詢/管理介面，`search_queries` 只能手動改 DB。

**使用者需求**（explore 討論定案）：
- 配置台：管理多組「平台 × 關鍵字 × 排程頻率」（後端/DevOps/SRE/Infra/雲端/架構師等方向）
- 職缺瀏覽台：按關鍵字/地區（含台北市各區）/薪資範圍/職缺類型篩選
- 收藏功能：同一個 app 裡加「加到最愛」
- 前後端分離部署（使用者明確選擇，目的是累積 SRE/Infra 轉職所需的多服務部署經驗，
  而非追求最少維運成本，見備註）
- 內網／個人使用，不做登入驗證
- 這次不做：關閉偵測、求職狀態追蹤

## Goals / Non-Goals

**Goals：**
- Dashboard 能管理爬蟲設定（CRUD `search_queries`）
- Dashboard 能依關鍵字/地區/薪資/類型查詢職缺
- 支援收藏
- 前後端分離部署，`api` 模組加 CORS

**Non-Goals：**
- 職缺關閉偵測、求職狀態追蹤（皆為未來 change）
- 登入驗證
- 爬蟲層的地區篩選（改在查詢層做，見 D2）

## Decisions

### D1：前後端分離部署（獨立 SPA + 獨立 Deployment），api 模組開 CORS

**決策**：React Admin build 出的靜態檔案，用 nginx 包成獨立 Docker image，獨立 k8s Deployment；
`api` 模組加 Spring `WebMvcConfigurer` CORS 設定，允許 frontend 的來源打 API。

**理由**：使用者明確選擇（見 proposal.md），這是刻意的取捨——job-radar 同時是面試作品集
（目標 SRE/Infra），比起最省維運成本的方案，使用者更想累積「多服務部署、CORS 設定、獨立
CI/CD pipeline、K8s 多 Deployment」這些真實場景的動手經驗。

**被否決的選項**：把前端靜態檔塞進 `api` 模組的 `resources/static/` 一起出（單一服務、
無 CORS 問題、部署最簡單）——技術上更省事，但被使用者否決，理由同上。

---

### D2：地區篩選在查詢層做，不在爬蟲層做——但兩平台的實際能力已重新查證

> **2026-07-21 更新**：本節原本假設「兩平台都做不到區級伺服端篩選」，經過實際訪問兩個
> 平台前端（比對真實瀏覽器發出的請求）重新查證，這個假設**部分錯誤**，見下方更正。

**決策維持不變**：`search_queries` 不因地區而拆分成多列（不做「關鍵字 × 地區」的組合爆炸）。
爬蟲維持「一個關鍵字一列」，廣泛爬（不帶地區參數，或只帶城市級）；地區資訊在 Normalizer
階段從平台原始 payload 抽取存進 `jobs` 表的可查詢欄位；Dashboard 查詢時才依地區篩選
（`WHERE district = '信義區'`），資料早就在 DB 裡，篩選不需要重新爬。

**理由修正**：
- ~~兩平台技術上做不到區級篩選~~——這是錯的。CakeResume 實測確認可以做到精準區級
  伺服端篩選（見 D3 更正），Yourator 則確認只有縣市級（22 個直轄市/縣市代碼，無區級）
- 真正的理由是：若要「6 個方向 × N 個台北市行政區 × 2 平台」都在爬蟲層各自發一個
  `search_queries` 列，N 稍微大一點就會讓總列數乘到 60+ 列，每輪都要對外部平台發大量請求，
  違反「同來源低並發、間隔禮貌」的硬規則（CLAUDE.md）——這個顧慮跟平台技術能力無關，
  是刻意的請求量控制取捨
- 地區資料本來就存在於平台回應裡，Phase 002 沒有抽取到可查詢欄位——這次補上即可

**被否決的選項**：`search_queries` 加 `district` 欄位、每個關鍵字對每個區各開一列——
即使 CakeResume 技術上做得到，請求量仍會隨地區數線性放大，不符合這次的請求量控制目標

---

### D3：`jobs` 表新增地區欄位，兩個來源的 parser 補抽取邏輯（已用真實資料查證修正）

**決策**：新增 `jobs.district`（區級，如「信義區」）與 `jobs.city`（城市級，如「台北市」）
兩個欄位，皆可 null。

**資料來源（已實測，非假設）**：

- **Yourator**：JSON-LD 的 `jobLocation.address.addressLocality` 固定是縣市層級（如
  「臺北市」），只能填 city。區級資訊只存在於 `streetAddress` 自由文字欄位，且**位置不固定**
  （實測 3 筆真實職缺：「內湖區瑞光路335號」區在最前面、「臺北市中正區和平西路...」區在
  中間、「中山區中山北路...」區在最前面但無縣市前綴）。抽取邏輯：用正則在 `streetAddress`
  全文找「[中文]{2,3}區」pattern，抓得到就填 district，抓不到就留 null（best-effort，
  不保證每筆都抓得到）
- **CakeResume**：`locations` 陣列**段數不固定**，依地區精確度而變：
  - 3 段「區, 市, 國」（如「信義區, 台北市, 台灣」）→ 第一段 district、第二段 city
  - 2 段「市, 國」（如「Taipei City, Taiwan」）→ 第一段 city，district 留 null
  - 1 段（純國家名）→ 全部留 null
  - 原本設計「固定第一段是 district、第二段是 city」是錯的，2 段情況會把 city
    誤存成 district，已修正為先判斷段數再分配

**兩平台縣市名稱寫法不一致，需要正規化**：Yourator 用「臺北市」（異體字「臺」），
CakeResume 用「台北市」（通用字「台」）。跨平台地區篩選比對時兩者字串不相等會漏資料。
**決策**：`city`/`district` 欄位儲存時統一正規化成「台」（通用字），Yourator parser
抽取時做 `臺→台` 字元替換。

**理由**：兩平台的地區資料粒度、格式都不同，不強行統一成一個「標準地區代碼」——沿用
Phase 002「保留平台原始語意、各自 parser 各自處理」原則，但縣市名稱正規化是例外——
這不是語意差異，是同一個字的兩種寫法，必須統一

---

### D4：REST API 依 `ra-data-json-server` 慣例設計（配合 React Admin）

> **修正**：本節原本寫 `ra-data-simple-rest`，但那個套件實際用的是
> `range=[0,9]&sort=["field","ASC"]&filter={"q":"bar"}` 這種 JSON 編碼參數，跟下面決策
> 記錄的 `_start`/`_end`/`_sort`/`_order` 分開參數格式對不上——這裡的慣例其實是
> `ra-data-json-server` 的格式，`api` 模組（`SearchQueryController`/`JobController`）
> 也已經照這個格式實作，這裡只是更正文件上的套件名稱，行為不變。

**決策**：
- 列表端點回應帶 `X-Total-Count` header（總筆數，React Admin 分頁用）
- 分頁參數：`_start`、`_end`（記錄範圍）
- 排序參數：`_sort`、`_order`（ASC/DESC）
- 篩選參數：直接用欄位名當 query param（如 `?district=信義區&salaryMin=1000000`）

**理由**：`ra-data-json-server` 是 React Admin 官方維護的 data provider 之一，慣例單純
（分開的 query param，不用 JSON 編碼進單一參數），後端只要照這個慣例出 API，前端幾乎
零客製化就能接上，不用另外寫 adapter 層

---

### D5：`search_queries.source` 在前端做成受限下拉選單，不開放自由輸入

**決策**：React Admin 的 Create/Edit 表單，`source` 欄位用 `<SelectInput>`，選項固定為
`["yourator", "cakeresume"]`（跟代碼裡實際註冊的 `JobListScraper`/`RawPayloadParser` 對齊）。

**理由**：`ScanService.runScan()` 用 `scrapersBySource.get(query.source())` 查表，查不到會
靜默跳過（不報錯、不爬），如果前端讓使用者打錯字（如 "yourater"），使用者只會看到「這組設定
一直沒有新職缺」卻不知道為什麼。用下拉選單在輸入端就擋掉這個問題，比在後端加驗證回錯誤訊息
更直接（雖然後端 API 仍應該做基本驗證，見 job-favorites/search-query-management-api specs）

---

### D6：`favorites` 表不需要 user_id

**決策**：`favorites (id, source, source_job_id, created_at)`，`(source, source_job_id)` unique。

**理由**：架構文件（architecture.md）Non-goals 明確「使用者就是本人＋未來少數同事」，不做多
使用者系統；加 user_id 是為不存在的需求預先設計，違反「不要為假設的未來需求做設計」的守則

---

### D7：`search_queries` 種子資料——每個平台一列，綁定全部方向，不做地區分流

> **2026-07-21 更新**：原本規劃「6 個方向各開一列」，但 D8 發現 Yourator 的
> `category[]` 單一值過濾不可靠（後端工程/全端工程/DevOps SRE 這三個目標分類剛好都在
> 不可靠之列），若照原計畫拆成 6 列、每列只帶 1 個分類，會有一半的方向實際上完全沒被
> 過濾。改為下方的合併方案。

**決策**：V5 migration 新增種子資料，**每個平台一列**，`categories` 欄位一次帶滿全部
6 個方向（Yourator 帶分類中文名稱陣列，CakeResume 帶 professions 代碼陣列），不拆成
6 列。好處：
- 繞開 Yourator 單一分類值不可靠的限制（見 D8）
- 減少請求量（一輪一次掃完全部方向，不用 6 次個別掃描）
- 之後若要更細緻的排程控制（例如某個方向想要不同的 `intervalMinutes`），使用者可以在
  Dashboard 配置台自行拆成多列，只是拆的時候要記得 Yourator 那邊每列至少放 2 個分類

`location`／`categories` 依平台實際查得到的能力填寫，不做地區分流（呼應 D2）。

---

### D8：新增 `search_queries.categories`，Yourator 用分類系統取代自由關鍵字，CakeResume 修正地區篩選 bug

> 本節基於實際訪問 yourator.co、cake.me 前端（比對真實瀏覽器發出的請求）查證後新增，
> 過程記錄見對話紀錄，這裡只記結論。

**決策 1——Yourator 有結構化分類系統，比自由文字關鍵字精準，改用它**：

`GET /api/v4/job_categories`（未在 source-api-notes.md 記錄過的端點）回傳完整分類樹，
其中涵蓋使用者要的方向：

| id | 分類名稱 |
|----|---------|
| 23 | 後端工程 |
| 24 | 全端工程 |
| 42 | 資料庫 |
| 28 | DevOps / SRE |
| 54 | 雲端工程師 |
| 55 | 系統架構師 |

`GET /api/v4/jobs` 的 `category[]` 參數**實測可以正確過濾**，但用值是分類的**中文名稱**
（不是數字 id，數字 id 完全無效，實測回傳未過濾的預設列表）。

**重要限制——單一分類值的過濾不可靠**：實測發現 `category[]` 只帶一個值時，
行為不一致——「資料庫」「系統架構師」單獨帶會正確過濾（分別回 3 筆、2 筆，皆相關），
但「後端工程」「全端工程」「DevOps / SRE」單獨帶**完全不過濾**（回傳跟不帶 category
一樣的預設列表，混雜大量不相關職缺，如 PCB 工程師、麥當勞店經理）。**帶 2 個以上分類值
一起送，全部都正確過濾**。這個不一致行為看起來是 Yourator 後端本身的問題，不是我們
猜錯參數格式；因此規則訂為：**`category[]` 一律綁定多個值一起送，不單獨送一個值**。

**決策 2——`search_queries` 新增 `categories` 欄位（JSONB，字串陣列，可 null）**：
沿用既有 `attrs` 欄位的 JSONB 慣例（`PgJson` helper），不引入 PostgreSQL 原生陣列型別。
- Yourator：`categories` 存分類中文名稱陣列（如 `["後端工程","全端工程","資料庫",
  "DevOps / SRE","雲端工程師","系統架構師"]`），adapter 組 `category[]=X&category[]=Y...`
- CakeResume：`categories` 存 `professions` 代碼陣列（如 `["it_back-end-engineer",
  "it_devops-system-admin","it_system-architecture"]`，代碼來自既有的
  `available_facets.professions`，Phase 002 調查階段已確認有效）
- `keyword` 欄位保留，但變成可選的自由文字補充篩選，不再是唯一的過濾手段——一個
  `search_queries` 列可以只靠 `categories` 過濾、`keyword` 留空字串

**決策 3——修正 CakeResume 的地區篩選 bug（已寫入既有代碼，需要修）**：

`CakeResumeListScraper.java`（`add-multi-source-cakeresume` change 交付的代碼）目前用
`filters.location`（**單數**、字串）傳地區，**實測這個欄位從頭到尾沒有真的在過濾**——
拿明顯不相關的地區（東京）測試，`total_entries` 完全不變，證明是 no-op。正確欄位是
`filters.locations`（**複數**、陣列），且值必須是 `available_facets.locations` 回傳的
**完整字串**（如「信義區, 台北市, 台灣」），不能只填「Taipei」這種簡短值。修正後實測
確認可以做到精準區級篩選（「台北市, 台灣」55 筆 vs 「信義區, 台北市, 台灣」3 筆）。

這代表 `search_queries.location` 這個既有欄位（Phase 002 就有）的語意也要跟著修正：
CakeResume 的 `location` 值必須存**完整 facet 字串**，不能像現在文件寫的存「Taipei」
這種簡短值。

**理由**：使用者明確要求「怕搞錯」，實際查證後發現原本的假設（CakeResume 地區篩選、
Yourator 只能靠自由關鍵字）都不準確，這裡如實記錄查證過程與修正後的結論，並修正已經
寫進代碼的既有 bug

**被否決的選項**：繼續用自由文字 `term[]`／`query` 關鍵字比對「後端」「DevOps」等字——
不精準（例如 Yourator 的 `term[]=後端工程師` 可能漏掉標題寫「Backend Engineer」的職缺），
且職稱五花八門難以窮舉；改用平台自己的分類系統精準度高很多

---

### D9：前端技術棧——TypeScript + React Admin + Vite，不用 Next.js，不額外引入 Tailwind/axios

**決策**：
- **語言**：TypeScript（React Admin 本身用 TS 寫、型別支援完整；使用者是 Java 背景，
  靜態型別對他來說是更熟悉的心智模型）
- **框架**：React Admin（非純 React、非 Next.js）
- **建置工具**：Vite，用官方 `npm create react-admin` 腳手架建立專案骨架（已內建
  Vite + TypeScript + data provider 基本設定），不手動從空的 Vite 專案兜
- **CSS**：全部用 MUI（React Admin 原生的樣式系統，`sx` prop/`styled()`），包含自訂的
  職缺卡片元件也用 MUI 寫，不引入 Tailwind——避免兩套樣式系統同時存在的維護成本
  （Tailwind 的 preflight CSS reset 會跟 MUI 的基礎樣式衝突，需要手動關掉，多一層要
  debug 的東西）
- **API 呼叫**：不額外引入 axios。`ra-data-json-server`（見 D4）底層已用 `fetch` 處理
  所有標準 CRUD，包含收藏功能（註冊成一個 React Admin resource，走同一個 data provider）
- **測試**：這次不寫前端自動化測試，用瀏覽器手動驗證功能正常即可

**理由**：使用者的優先順序是「前端只要懂大概原理、看得懂代碼、能快速搭起來」，真正的
興趣在後端高併發架構，不是深入前端生態系統。基於這個前提：

- **為什麼不是 Next.js**：Next.js 的核心賣點是 SSR/SSG（通常為了 SEO 或公開頁面首屏
  速度）與 API Routes（框架內建後端能力）。這個 dashboard 沒有 SEO 需求（個人內部工具，
  沒有訪客會被搜尋引擎索引），也已經有獨立的真正後端（`api` 模組），Next.js 的兩個
  主要賣點都用不到，引入它只會多一層框架特有的規則（App Router、Server/Client
  Component 的分界）要學，卻換不到對應好處
- **為什麼是 React Admin 而非純 React**：與先前討論（純 React 換取更高學習價值）的
  結論相反——當「懂大概、看得懂」是優先目標時，React Admin 的宣告式寫法
  （`<List>`、`<Datagrid>`、`<TextField>` 這類元件，名稱即用途）比純手刻的 React
  （資料抓取、狀態管理、路由邏輯分散在更多地方、客製化程度高）更容易掃視理解，
  AI 產生的 React Admin 代碼也因為模式固定而更一致好驗證
- **為什麼不引入 Tailwind**：先前（純 React 路線）曾規劃「職缺卡片用 Tailwind、配置台
  用 MUI」的混合方案，但確定改走 React Admin 為主之後，維持單一樣式系統的簡單性
  價值大於「多學一套 CSS 系統」，故收回原本的 Tailwind 建議

**被否決的選項**：
- Next.js（原因如上）
- 純 React + 自建路由/資料抓取（原本的推薦，因使用者優先順序改變而收回，見上）
- Tailwind + MUI 混合（同上，優先順序改變後不再需要）
- axios（`ra-data-json-server` 的 fetch 已足夠涵蓋需求，不需要第二套 HTTP 機制）

---

## 附註：與本 change 無關但查證過程順帶發現的事（記錄備查，不在本 change 處理）

- **Yourator 的 `sort=recent_updated`**：實測是真的按更新時間排序（連續 10 筆皆「一天內
  更新」），這推翻了 `add-walking-skeleton/design.md` D6 決策的前提假設（該處寫「不確定
  除了 most_related 還有沒有時間排序」）。這代表 Yourator 的 `YouratorListScraper` 現行
  「固定翻 10 頁全量掃描」策略，有機會改成真正的游標式 early termination（翻到看到舊職缺
  就停），能大幅減少不必要的請求。**這個發現留給獨立的 change 處理**，不在
  `add-job-dashboard` 範圍內，避免範圍蔓延

## 已知限制（本輪端到端驗證時發現，先記錄不處理）

- **`search_queries` 的 `UNIQUE (source, keyword)` 約束與 categories 機制有摩擦**：
  這個約束是 V1 schema 時代的設計，當時假設每列 `keyword` 都不同、天然唯一。D8 導入
  `categories` 後，`keyword` 常常是空字串（見 D7、D8 決策 2），如果使用者想替同一個
  `source` 開兩列、都用空 keyword、只靠不同的 `categories` 區分，會撞到這個約束
  （`api` 端已確保回 409 而不是 500，見 search-query-management-api spec，但底層約束
  本身還沒調整）。之後若要支援「同平台多組純 categories 設定」，需要重新設計這個唯一鍵
  （選項：拿掉這個約束、改成應用層檢查；或約束改成 `(source, keyword, categories)`，
  但 JSONB 欄位放進 UNIQUE 約束需要額外處理）。這次先不動，等實際使用後再評估優先度

## Risks / Trade-offs

| Risk | 影響 | 緩解 |
|------|------|------|
| CORS 設定錯誤 → 前端打不進 API | Dashboard 完全無法使用 | 開發階段先用瀏覽器 devtools 確認 preflight request 正確；正式環境的 origin 白名單要對應 k8s 實際網域 |
| Yourator 的區級地址資料可能不夠結構化 | `district` 欄位大量是 null | 先只保證 `city` 有資料，`district` 為 best-effort，前端篩選時允許「不篩地區」或「只到城市級」 |
| React Admin 的 data provider 慣例跟 Spring Boot 預設分頁（Pageable）不一致 | 要手動處理分頁參數轉換，不能直接用 Spring Data 的自動分頁 | Controller 層手動解析 `_start`/`_end`/`_sort`/`_order`，不依賴 Spring Data JPA 的 Pageable（本專案本來就用 JdbcClient，不是 JPA，不衝突） |
| 獨立前端服務多一份 CI/CD 與 k8s 資源 | 維運成本增加 | 使用者已知情並主動選擇（D1），非本 change 需要解決的風險 |

## Migration Plan

1. **修 CakeResumeListScraper 既有 bug**（`filters.location`→`filters.locations`，見 D8
   決策 3）——這個修復獨立於本 change 其他項目，應優先做，因為是既有代碼的正確性問題
2. DB migration（V5）：`jobs` 加 `district`/`city`；`search_queries` 加 `categories`
   （JSONB）；新增 `favorites` 表；種子資料擴充（含 categories 值）
3. `collector`：`YouratorListScraper` 支援組 `category[]` 多值參數（見 D8 決策 1/2）；
   `CakeResumeListScraper` 支援組 `professions` 參數（用 `categories` 欄位）
4. `worker`：兩個 parser 補地區抽取邏輯（含臺/台正規化，見 D3）、`NormalizedJob` 加對應欄位
5. `api`：新增三組 Controller（search-queries CRUD、jobs 唯讀查詢、favorites CRUD）+ CORS 設定
6. `frontend/`：新建 React + React Admin 專案，本機用 `npm run dev`（Vite proxy 轉發 API
   請求）驗證功能；配置台的 categories 欄位做成多選（Yourator 選分類名稱、CakeResume 選
   professions 代碼，皆為固定選項清單，不開放自由輸入，同 D5 的 source 限制精神）
7. 本機端到端驗證：配置台改設定 → Collector 真的照新設定爬（驗證 category[]/professions/
   locations 真的生效，不是又踩到「單值不過濾」之類的坑）→ 職缺瀏覽台看得到、篩得到 →
   收藏功能正常寫入/查詢
8. CI/CD 與 k8s 部署留待下一步（見 proposal.md Non-goals）

## Open Questions

1. ~~Yourator 的區級地址精確度到底如何~~——已查證：只有縣市級結構化欄位，區級只能靠
   `streetAddress` 正則解析，best-effort（見 D3）
2. ~~CakeResume 的 `locations` 字串格式是否固定~~——已查證：段數不固定（1-3 段），
   解析邏輯已修正為先判斷段數（見 D3）
3. **新增**：Yourator `category[]` 單值不過濾的行為是否對所有分類都適用，還是只有測過的
   「後端工程/全端工程/DevOps SRE」三個？實作階段若要支援「只選一個分類」的配置，需要
   先確認這個限制的完整範圍，或乾脆在 UI／API 層強制至少選 2 個分類
4. **新增**：CakeResume 的 `professions` 代碼列表（`available_facets.professions`）
   是否穩定不變？如果平台之後調整代碼命名，`search_queries.categories` 存的舊代碼可能
   失效，需要考慮要不要在配置台加「重新整理可用選項」的機制（可以留給更後面的 change）
