# Tasks: add-job-dashboard

## 0. 查證與既有 bug 修復（本輪已完成，非原始計畫項目，過程中發現需要先處理）

- [x] 0.1 實際訪問 yourator.co、cake.me 前端（比對真實瀏覽器發出的請求），查證兩平台的
      篩選參數是否如原始設計假設——發現多處需要修正，詳見 design.md D8
- [x] 0.2 修復 `CakeResumeListScraper.java` 既有 bug：`filters.location`（單數、字串，
      no-op）改成 `filters.locations`（複數、陣列，實測確認真的過濾），已用真實 API 驗證
      （地區 55 筆 vs 區級 3 筆，過濾確實生效）
- [x] 0.3 找到 Yourator 未記錄過的 `/api/v4/job_categories` 分類系統端點，確認 `category[]`
      對 `/api/v4/jobs` 真的生效，但單一值不可靠（部分分類單獨帶會被忽略），需綁定多值
- [x] 0.4 `PgJson` helper 從 `worker.normalizer` 搬到 `common.db`，讓 `collector` 也能共用
      （`common/build.gradle.kts` 加 `org.postgresql:postgresql` 依賴）

## 1. DB Schema 變更（Flyway Migration V5，已完成）

- [x] 1.1 新增 `search_queries.categories`（JSONB，字串陣列，可 null）——取代原計畫的
      「加 district 欄位到 search_queries」，改為在查詢層／既有 location 欄位上做分類篩選
      （見 design.md D8）
- [x] 1.2 新增 `favorites` 表：`id`、`source`、`source_job_id`、`created_at`，
      `(source, source_job_id)` unique constraint
- [x] 1.3 修正既有 CakeResume 種子資料：`location` 從 `"Taipei"` 改成完整 facet 字串
      `"台北市, 台灣"`（呼應 0.2 的 bug 修復）；兩個來源的 `keyword` 清空，改以
      `categories` 為主要篩選手段
- [x] 1.4 種子資料改為「每個平台一列，一次帶滿全部 6 個方向」，不是原計畫的 12 列
      （見 design.md D7 更新——Yourator 單一分類值不可靠，一次帶滿可繞開這個限制，
      同時減少請求量）
- [x] 1.5 本機驗證 migration：對含 162+74 筆真實 Yourator 資料的既有 DB 套用 V5，
      Testcontainers 全 migration 鏈（V1-V5）與本機 podman-compose 環境皆驗證通過

- [x] 1.6 `jobs.district`、`jobs.city` 欄位——已在第 2 節完成（parser 抽取邏輯 + upsert
      寫入），這裡當時寫的時候第 2 節還沒做，後續完成後忘記回頭勾選，這裡補正

## 1a. Collector Module：分類篩選落地（已完成）

- [x] 1a.1 `SearchQuery` domain record 加 `categories: List<String>` 欄位
- [x] 1a.2 `SearchQueryRepository` 讀取 `categories`（JSONB 反序列化）
- [x] 1a.3 `YouratorListScraper`：支援組多個 `category[]` 參數；`term[]` 改成只在 keyword
      非空白時才送出；單一分類值時記錄警告（見 0.3 的限制）
- [x] 1a.4 `CakeResumeListScraper`：支援組 `filters.professions` 陣列參數
- [x] 1a.5 端到端驗證（真實 API）：Yourator 192 筆、CakeResume 200 筆，皆用真實請求驗證
      分類/專業過濾確實生效（樣本標題檢查，Yourator 全部相關；CakeResume 大多數相關，
      少數平台自己分類不準的雜訊屬預期範圍，非本專案能控制）
- [x] 1a.6 單元測試更新（`YouratorListScraperTest`、`CakeResumeListScraperTest` 因
      `SearchQuery` record 簽名改變同步更新，並新增 `filters.locations` 格式的斷言避免
      bug 回歸）

## 2. Worker Module：地區欄位抽取（已完成）

- [x] 2.1 `jobs` 表加 `district`、`city` 欄位——**不用額外開 V6**，發現 V5 早就加過了
      （寫 design.md D3 時就順手寫進 migration，tasks.md 當時漏更新狀態）
- [x] 2.2 `NormalizedJob` 加 `district`、`city` 欄位（14 個欄位，向後兼容 6 參數／12 參數
      constructor 同步保留，既有呼叫端不用改）
- [x] 2.3 `YouratorRawPayloadParser` 補 city 抽取（`jobLocation.address.addressLocality`，
      固定縣市層級）+ district best-effort 抽取（`streetAddress` 用正則找
      `[一-鿿&&[^市縣區]]{2,3}區`——字元類特別排除市/縣/區本身，否則
      「臺北市中正區...」這種字串會誤抓成「市中正區」）
- [x] 2.4 `CakeResumeRawPayloadParser` 補 district/city 抽取，先判斷 `locations[0]`
      逗號切分後的段數（3 段=區,市,國；2 段=市,國；1 段/空陣列=全部留 null）
- [x] 2.5 兩個 parser 的 city 正規化：Yourator 的「臺」統一轉換成「台」，已用真實資料
      驗證（「臺北市」正確存成「台北市」）
- [x] 2.6 `JobRepository.upsert()` 加 district/city 的 INSERT/UPDATE 邏輯
- [x] 2.7 單元測試：兩個 parser 各新增 3 個測試（城市/區抽取的三種真實字串形狀、
      空值/格式不符預期），共 10 個測試全過
- [x] 2.8 端到端驗證（真實 API + 真實 DB）：Yourator 137/373、CakeResume 192/366 筆
      正確填入 city，區級抽取皆正確（信義區/內湖區/中山區等），0 個與本次改動相關的錯誤
      （抽查到 6 個 error 是既有已知問題——舊格式 Kafka 訊息殘留，與這次改動無關）

## 3. API 模組：search-queries CRUD（已完成）

- [x] 3.1 SearchQueryController：GET（list，支援 `_start`/`_end`/`_sort`/`_order`）、
      GET/:id、POST、PUT/:id、DELETE/:id
- [x] 3.2 List 端點回應帶 `X-Total-Count` header（另外也加了 `Content-Range`，React Admin
      兩種 data provider 慣例都可能用到）
- [x] 3.3 POST/PUT 驗證 `source` 只能是已註冊的來源（yourator/cakeresume），
      不符合回 400（見 design.md D5）——**實作偏離**：`categories` 欄位格式驗證交給
      Jackson 反序列化自然把關（非陣列格式會直接 400），沒有另外寫格式檢查邏輯
- [x] 3.4 DELETE 時一併刪除對應的 `scrape_cursors` 記錄
- [x] 3.5 單元/整合測試：`SearchQueryRepositoryTest`（Testcontainers，7 個測試）+
      端到端真實 HTTP 驗證（見 8.1）

**端到端驗證額外發現並修復的問題**：
- [x] 3.6 `(source, keyword)` 唯一約束與 categories 機制衝突時，原本會讓 API 回 500，
      已加 `@ExceptionHandler` 改回 409 並附清楚訊息；根本限制記錄在 design.md
      「已知限制」，這次不調整 schema

## 4. API 模組：jobs 唯讀查詢（已完成）

- [x] 4.1 JobController：GET（list，支援 q/district/city/salaryMin/salaryMax/jobType/
      source/status 篩選 + `_start`/`_end`/`_sort`/`_order`）
- [x] 4.2 GET /:id（單筆詳情，含 isFavorited 欄位）
- [x] 4.3 List 端點預設排除 status='CLOSED'（見 job-browse-api spec）
- [x] 4.4 List 端點回應帶 `X-Total-Count` header
- [x] 4.5 單元/整合測試：`JobRepositoryTest`（Testcontainers，7 個測試，涵蓋關鍵字/地區/
      薪資範圍/預設排除 CLOSED/明確指定 status）+ 端到端真實資料驗證

## 5. API 模組：favorites CRUD（已完成）

- [x] 5.1 FavoriteController：GET（list）、POST、DELETE/:id——**實作偏離**：GET list
      這次沒有 join jobs 基本資訊（原計畫），先只回 favorites 表本身的欄位，前端要顯示
      職缺標題等資訊時用 sourceJobId 另外查 `/api/jobs`，避免這次把兩個 repository
      耦合在一起；如果之後前端這樣用起來體驗不好，再回頭加 join
- [x] 5.2 POST 冪等處理：ON CONFLICT DO UPDATE 一步到位（不用先查再寫），重複收藏同一筆
      回傳既有記錄的 id，已用真實 HTTP 請求驗證
- [x] 5.3 jobs 查詢端點（4.1/4.2）加上 isFavorited 判斷邏輯——list 用批次查詢
      （pair-key 比對，避免逐筆查 DB，也避免 source/sourceJobId 各自 IN 比對的跨組合
      誤判，見 FavoriteRepository 實作備註），detail 用單筆查詢
- [x] 5.4 單元/整合測試：`FavoriteRepositoryTest`（Testcontainers，7 個測試，含跨組合
      誤判的專門測試案例）+ 端到端真實 HTTP 驗證（收藏 → 列表/詳情顯示 → 取消收藏 →
      確認清空）

## 6. API 模組：CORS 設定（已完成）

- [x] 6.1 新增 `WebMvcConfigurer` bean，允許的 origin 用環境變數設定
      （`API_CORS_ALLOWED_ORIGINS`，預設含 Vite dev server 的 5173 port）
- [ ] 6.2 驗證：本機用瀏覽器 devtools 確認 preflight request 正確——**尚未驗證**，
      因為前端專案還沒建立（見第 7 節），這次只驗證了 API 本身用 curl 直接打沒問題，
      真正的跨 origin 瀏覽器請求要等前端起來才能測

**端到端驗證額外發現並修復的問題（不在原始 tasks 清單）**：
- [x] 6.3 `api` 模組缺少 `net.logstash.logback:logstash-logback-encoder` 依賴，
      但 `logback-spring.xml` 設定檔用了 `LogstashEncoder`，導致 `api` 應用程式
      完全無法啟動（Phase 001 就存在的既有 bug，這次第一次真的啟動 api 模組才發現）

## 7. Frontend：React Admin 專案建置

技術棧見 design.md D9：TypeScript + React Admin + Vite（`create-react-admin` 腳手架）+
`ra-data-json-server`，全部用 MUI 樣式（不引入 Tailwind/axios），不寫自動化測試。

- [x] 7.1 用 `npm create react-admin`（`--data-provider json-server --auth-provider none`）
      建立 `frontend/` 專案骨架，已內建 TypeScript + Vite + ESLint/Prettier
- [x] 7.2 `vite.config.ts` 加 `/api` proxy 轉發到 `http://localhost:8083`（本機開發用），
      `.env` 的 `VITE_JSON_SERVER_URL` 設為 `/api`；已用真實請求驗證 proxy 正確轉發、
      `X-Total-Count` header 有正確帶到前端
- [x] 7.3 Resource: search-queries（List 用標準 Datagrid、Create/Edit 共用同一個表單）
      ——source 用 `<SelectInput>` 限制選項（見 D5）；categories 用 `<FormDataConsumer>`
      依當下選的 source 動態切換選項清單（Yourator 分類名稱／CakeResume professions 代碼，
      皆定義在 `categoryOptions.ts`，固定清單不開放自由輸入）
- [x] 7.4 Resource: jobs（List 用自訂 `JobCard`/`JobGrid` 取代預設 Datagrid，見 D9；
      Show 用自訂內容呈現；篩選器：關鍵字/區/縣市/薪資範圍/職缺類型/來源）
- [x] 7.5 收藏按鈕（`FavoriteButton`，列表卡片與詳情頁共用）：`favorites` 不註冊成
      `<Resource>`（沒有獨立頁面），但 `useCreate`/`useDelete` 呼叫時一樣經過同一個
      data provider——**實作階段發現並修正一個設計缺口**：`JobResponse` 原本只有
      `isFavorited: boolean`，取消收藏需要 favorite 自己的 id 才能呼叫
      `DELETE /api/favorites/:id`，Job 本身沒有這個 id。已補上 `favoriteId` 欄位，
      `FavoriteRepository` 對應方法從回布林值改回 id（`findFavoriteIdsByPairKeys`／
      `findFavoriteId`），連帶更新 `FavoriteRepositoryTest`
- [x] 7.6 Dockerfile——**尚未做**，跟 tasks.md 第 9 節（CI/CD/k8s）一起留到真的要部署時處理
- [x] 7.7 本機驗證（見 8.4）

**端到端驗證額外發現並修復的問題（不在原始 tasks 清單）**：
- [x] 7.8 `ra-data-json-server` 的 `delete()` 會讀 DELETE 回應 body 當結果，我們的
      `SearchQueryController`/`FavoriteController` 原本回空 body（204/200 無內容），
      改成回 `{"id": ...}`，避免前端解析出 `data: undefined` 造成 React Admin 的
      列表快取更新異常（查 `ra-data-json-server` 原始碼發現，未在瀏覽器親自跑到才確認，
      是讀套件行為推斷出來的，正式驗證有沒有問題留給 8.4）

## 8. 端到端驗證

- [x] 8.0 分類/地區篩選的爬蟲端驗證（見 1a.5，提前完成，因為是本輪修復的核心）
- [x] 8.1 配置台 API：真實啟動 `api` 服務，對真實 DB 做完整 CRUD 驗證（GET 列表/單筆、
      POST 新增（含撞唯一約束的 409、單分類警告 header）、PUT 更新、DELETE 刪除+
      cascade 清 scrape_cursors）——**尚未驗證的部分**：前端配置台改設定後
      Collector 下一輪真的照新設定爬，這需要等第 7 節前端做完、或另外手動排一次
      collector 才能測到「從 UI 改設定」這個完整路徑，目前驗證的是 API 本身正確
- [x] 8.2 職缺瀏覽台 API：真實 HTTP 驗證關鍵字/地區/薪資範圍篩選皆正確（對真實累積的
      Yourator/CakeResume 資料驗證，結果皆相關）；前端畫面本身留給第 7 節
- [x] 8.3 收藏功能：真實 HTTP 驗證收藏 → 冪等重複收藏 → 列表/詳情顯示 isFavorited →
      取消收藏 → 確認清空，全部正確
- [x] 8.4 前端靜態驗證（**不是**瀏覽器層級的完整驗證，見下方限制說明）：
      `tsc --noEmit` 型別檢查通過、ESLint 通過、`vite build` 正式建置成功、
      `npm run dev` 起開發伺服器後真實請求驗證 Vite proxy 正確轉發 `/api/*` 到
      `api` 模組並拿到真實 DB 資料（含 `X-Total-Count` header 正確帶到前端）、
      個別檔案（`App.tsx`/`JobCard.tsx`/`SearchQueryForm.tsx`）皆能被 Vite 正確
      transform（無語法/import 錯誤）。**尚未驗證、且這次環境做不到的部分**：
      沒有瀏覽器工具，無法實際看畫面渲染、點擊收藏按鈕、確認篩選器真的能操作、
      確認跨 origin 情境下瀏覽器真的放行 CORS preflight——這些需要使用者自己在
      瀏覽器打開 `http://localhost:5173` 實際操作才能確認
- [x] 8.5 `gradle test` 全數通過（含新增的 21 個 api 模組測試，Testcontainers 對真實
      Postgres 跑過完整 migration 鏈；前端這次依約定不寫自動化測試，見 D9）

## 9. 文件與後續（暫緩，跨 repo 或延後處理）

- [ ] 9.1 更新 docs/architecture.md（Roadmap Phase 003 標記進度、補充地區抽取的資料來源說明）
- [ ] 9.2 CI/CD：`.gitlab-ci.yml` 新增 frontend build/test/image job（留待實際要部署時處理）
- [ ] 9.3 k8s repo：新增 frontend Deployment + Service（跨 repo，留待實際要部署時處理）

## 10. 記錄備查（不在本 change 處理）

- [ ] 10.1 Yourator `sort=recent_updated` 的發現——推翻 `add-walking-skeleton` D6 決策的
      前提假設，有機會改成真正的游標式 early termination。獨立開一個 change 處理
      （見 design.md 附註）
