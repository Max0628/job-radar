## Context

104 的 API 規格已經在 `docs/source-api-notes.md` 記錄完整（list/detail API、Area.json/
JobCat.json 靜態參考資料、Cloudflare 觀察）。`docs/architecture.md` D19/D22 已定案錯誤
處理策略與語言選型。這個 change 是把已經確認過的規格轉成程式碼，不是重新設計 API 行為。

## Goals / Non-Goals

**Goals:**
- 104 完整走過 `JobListScraper`/`DetailScraper`/`RawPayloadParser`/`FacetsClient` 四個
  既有介面，跟 Yourator/CakeResume 用同一套架構
- 套用比 Yourator/CakeResume 更保守的節流參數（D19）
- 全程 mock 測試，不打真實 104

**Non-Goals:**
- 不做 Area.json 反查、不做 `order`/更新時間篩選（見 proposal.md）

## Decisions

**分頁判斷：`metadata.pagination.currentPage < lastPage`，比照 CakeResume 的
累積筆數判斷手法，不是 Yourator 的 `hasMore` 布林值**
104 的 list API 回應有明確的 `lastPage`，判斷邏輯上更接近 CakeResume。**已知限制要
在程式碼裡明確處理**：平台端有約 3000 筆可觸及結果的硬上限，`lastPage` 隨 `pagesize`
變動但 `lastPage × pagesize` 恆約等於 3000（見 source-api-notes.md 兩次不同 pagesize
的實測記錄），這代表分頁迴圈到平台回的 `lastPage` 時會自然停止（`currentPage < lastPage`
變 false），不需要額外程式碼處理這個上限——既有的判斷邏輯本來就會在這個點正確停止，
只是拿不到上限以後的資料，這是平台限制，不是程式錯誤。

**104 detail scraper 不用 Yourator 那種 Resilience4j `@Retry` 註解，改用跟 list
scraper 一致的手刻三類分類迴圈**
Yourator 的 detail scraper 用 `@Retry(name="yourator")`（單一重試策略，遇到 429 就
重試）。104 的 detail 也需要「403/503 不重試」這個分支，Resilience4j 的
`retry-exceptions` allowlist 機制理論上也能做到（不在清單裡的例外不重試），但為了
跟 list scraper 的分類邏輯保持一致、避免兩套不同的錯誤分類心智模型混在同一個來源裡，
104 detail scraper 直接複用跟 list scraper 一樣的手刻分類寫法。
- 被否決：用 `@Retry` + 精心設計的 `retry-exceptions` 清單——技術上可行，但讓同一個
  來源（104）的 list 跟 detail 用兩套不同機制做同一件事，之後維護要同時理解兩套，
  增加認知負擔

**`district` 用字串處理（`addressRegion` 去掉 `addressArea` 前綴），不做
Area.json 反查**
104 detail 回應的 `addressNo` 理論上可以反查 `Area.json` 拿到乾淨的區級名稱，但這需要
worker 模組另外載入/快取一份 Area.json 資料，多一個依賴。Yourator 的 district 抽取
也是用正則字串處理（見 `YouratorRawPayloadParser.extractDistrict`），104 比照同一種
簡化手法：`addressRegion`（如「台北市信義區」）去掉 `addressArea`（如「台北市」）這個
前綴，剩下的就是區名。
- 被否決：載入 Area.json 做精確反查——多一個靜態資料依賴，換來的精確度提升在這個
  規模不成比例（Yourator 用字串正則也只是「堪用」，不是每個平台都追求同一種精確度）

**104 的節流參數透過 `CollectorScanProperties.sources` 覆寫，並新增請求間隔隨機區間
支援**
現有的 `requestIntervalMillis` 是全域單一值，Yourator/CakeResume 直接用
`properties.requestIntervalMillis()`。104 需要 3–10 秒「隨機」區間（見 D19），不是
固定值。`SourceOverrides` 新增 `requestIntervalMinMillis`/`requestIntervalMaxMillis`
（皆為 `Long`，null 代表沒覆寫、退回全域固定值），`Job104ListScraper` 自己在
`sleep()` 前算一個隨機值；Yourator/CakeResume 完全不受影響（它們的 `sources` map
裡沒有 104 這種覆寫，繼續讀全域 `requestIntervalMillis`）。

**`Job104FacetsClient` 即時打 `static.104.com.tw`，不快取到 `common` 模組的靜態檔案**
這兩個檔案已確認無 Cloudflare、變動極少。`FacetsService` 既有的 12 小時快取機制已經
避免了頻繁重打，不需要額外把 Area.json/JobCat.json 內容打包進程式碼——即時打一次、
之後靠 `FacetsService` 快取，做法上最接近既有的 Yourator/CakeResume `FacetsClient`
模式（它們也是即時打各自平台的 API，不是打包靜態資料）。

**`Job104RawPayloadParser` 刻意讓 `employmentType`/`seniorityLevel`/`langName`/
`minWorkExpYear`/`numberOfOpenings` 維持 `null`——但不是因為原始資料不存在**
2026-08-01 用真實 detail response 核對後發現，這幾個欄位其實**不是完全沒有對應的原始
資料**，只是沒有像 `salaryMin`/`salaryMax`/`city` 那樣可以直接取值，需要額外的文字解析：
- `minWorkExpYear` ← `condition.workExp`（文字，如「4年以上」，需正則解析數字）
- `numberOfOpenings` ← `jobDetail.needEmp`（文字，如「1~3人」，範圍格式，需正則解析）
- `langName` ← `condition.language[]`（實測樣本是空陣列，有值時的結構未知）
- `employmentType` ← 候選欄位 `jobDetail.hireType`（數字列舉，實測樣本值為 `0`，
  跟 `jobType` 一樣只有一個樣本點、列舉語意未知）
- `seniorityLevel`：目前沒有找到對應的候選欄位

刻意不做這些欄位的原因是**這次的範圍是把 104 走過四個既有介面，不是把每個可解析的
文字欄位都榨乾**——這類文字正則解析（`workExp`/`needEmp`）成本不高，但 `hireType`/
`language[]` 語意/結構都只有單一樣本點，貿然實作等於用猜測值餵給下游，跟被否決的
「硬把 `jobType` 塞進 `employmentType`」是同一類風險。留到之後有更多樣本、確認語意後
再實作，比現在猜測後之後要回頭修資料品質問題成本低。
- 被否決：現在就用單一樣本點的資料做正則/列舉解析——語意不對等或列舉值猜錯的欄位
  互相借用，之後排查資料品質問題時難以分辨「平台真的沒提供」跟「映射邏輯猜錯」

**`jobType` 直接透傳原始字串，不做正規化**
104 的 `jobDetail.jobType` 是平台自己的分類字串，跟 Yourator（大寫底線）、CakeResume
（小寫底線）的職缺類型字串格式都不同。比照 `JobList.tsx` 既有的「保留平台原始語意，
不強行統一」原則（見 `add-job-dashboard/design.md` D3），104 也直接透傳，不嘗試映射
成統一的枚舉值。

**`Job104ListScraper` 保留跟 Yourator/CakeResume 一致的重複頁安全網，即使 104 已有
明確的 `lastPage`**
104 的分頁判斷（`currentPage < lastPage`）理論上不會像 Yourator 那樣需要靠「連續回傳
同一批 job id」來偵測卡頁，但保留這個安全網是為了防禦 104 平台本身回應異常（例如
`lastPage` 欄位因為某次回應格式錯誤而回傳錯誤值、或分頁參數被忽略導致每頁回傳相同
內容）——這類異常不是「设计上会发生」，而是「防止未知的平台端 bug 讓爬蟲卡在無限迴圈」，
成本很低（幾行既有邏輯直接複用），保留比拿掉更安全。

**`Job104DetailScraper` 加上跟 `YouratorDetailScraper` 一致的 `synchronized`
`MIN_INTERVAL`（1 秒）速率閘門**
最初實作時遺漏了這個閘門，只靠 `ConcurrentKafkaListenerContainerFactory` 預設
`concurrency=1` 隱含的單執行緒序列化。這不夠：（1）這只是預設值的副作用，不是明確設計
出來的保證，之後如果有人為了吞吐量調高 concurrency 而沒意識到 104 的特殊性，會悄悄
破壞這個假設；（2）K8s 多副本部署下，多個 worker instance 各自跑一個執行緒，仍然可能
同時打 104 detail API。补上跟 Yourator 一樣的 `synchronized` gate，把「同一個 JVM 內
至少間隔 1 秒」變成程式碼明確保證的行為，不是隱含假設。
- 已知限制：這個 gate 只在單一 JVM 內生效，不是跨 K8s 副本的全域速率控制——多副本
  情境下仍可能超過「同來源並發＝1」的目標。可接受：目前部署是單副本（見
  `k8s/apps/job-radar` deployment 設定），多副本擴展如果之後真的發生，需要另外引入
  跨副本協調機制（例如 Redis 分散式鎖），這次不做超前部署

**面議職缺的 `salaryMin`/`salaryMax` 正規化成 `null`，不是存 `0`**
2026-08-01 真實 detail API 取樣（見下方 Risks 的驗證記錄）發現 104 面議職缺不是像
Yourator/CakeResume 那樣回傳 JSON `null`，而是 `salaryMin`/`salaryMax` 兩個欄位都填數字
`0`（樣本：`jobDetail.salary="待遇面議"` 時 `salaryMin=0`、`salaryMax=0`）。原本的實作
（改動前）沒有特判這個情況，會把 `0` 原樣存進 `NormalizedJob`，下游顯示會變成「時薪 0
元」而不是「面議」，是誤導性的錯誤資料。修正：`Job104RawPayloadParser` 偵測到
`salaryMin == 0 && salaryMax == 0` 時正規化成 `null`、`null`，跟 Yourator/CakeResume
「沒有薪資資訊就是 null」的語意一致。

**疑似被封鎖時自動停用整個 104 來源，重新啟用純手動——只做 104，不做成通用機制**
2026-08-01 討論後新增：104 沒有 cookie/自動復原機制（見上方風險），排程器預設每個週期
都會重打，如果被 Cloudflare 判定封鎖，放著不管等於每個週期持續打一個已知會失敗的
請求，拉高 IP 被鎖的風險。決定：list/detail 任一邊收到 403/503（`SourceBlockedException`，
見 `common.source` 新增的專用例外類型）時，立刻把 `search_queries` 裡**所有** 104 的
查詢設成 `enabled=false`（不是只關觸發失敗的那一筆——Cloudflare 風控是整個網域層級的
判定，不是針對單一查詢），並寫入 `disabled_reason`（V13 migration 新增的欄位）記錄
原因。重新啟用刻意設計成純手動（使用者在前端把 `enabled` 打勾存檔，複用既有的
表單欄位，不做新 UI），因為：（1）自動關閉的目的正是避免自動重試造成 IP 被鎖，若系統
自己過一段時間又自動打開，等於繞回原本要避免的問題；（2）現在的正式程式碼完全沒有送
cookie，等一段時間再打不保證會變回 200，「要不要再試」本質是人的判斷，不是系統能自己
判斷的事。`enabled` 存回 `true` 時 api 模組會自動清掉 `disabled_reason`（見
`SearchQueryRepository.update()`），不需要使用者另外清除。
- **範圍刻意限定只做 104**：Yourator/CakeResume 目前沒有這個風險意識（沒有已知的
  cookie-less 曝險情境），三來源共用的「blocked 分類」基礎設施（`add-source-error-alerting`）
  不變，只有 104 的 scraper 拋 `SourceBlockedException`，其餘來源繼續拋一般
  `IllegalStateException`（行為不變）。之後如果 Yourator/CakeResume 也需要，直接讓
  對應 scraper 改拋同一個例外類型即可重用整套機制，不需要重新設計
- **collector（list）跟 worker（detail）都會觸發**：104 的 list/detail 同網域、同一套
  Cloudflare 風控，任一邊被擋都視為整個來源被擋。worker 端因此新增一個窄範圍的
  `SearchQueryDisableRepository`（只有這一個寫入動作，不是完整 CRUD，worker 原本對
  `search_queries` 表沒有任何讀寫需求）
- **Kafka 消費端額外做法**：`SourceBlockedException` 註冊成
  `DefaultErrorHandler.addNotRetryableExceptions`，跳過既有的 3 次重試直接進 DLQ——
  對已知會失敗的請求重試沒有意義，只會浪費請求；訊息仍會進 DLQ，不會悄悄遺失（既有
  `JobRadarDlqNotEmpty` 告警會抓到）

**不 seed `search_queries`**
Yourator/CakeResume 上線時 migration 直接 seed 一筆啟用的查詢（`V2`/`V3`）。104 因為
「第一次掃描等同一次深掃」＋沒有自我恢復能力，這次刻意不比照辦理——上線後由使用者
自行決定何時、用多窄的範圍手動新增查詢，migration 只負責讓 `104` 這個 `source` 值
合法（`registeredSources()`），不負責建立第一筆資料。

## Risks / Trade-offs

- **[風險] fixture-based 測試無法真正驗證 104 的 Cloudflare 行為**（403/503 這類
  疑似風控回應在 mock 測試裡只是「模擬回應碼」，不代表真實 Cloudflare 就是這樣回應）
  → 接受：這正是 D20 要求真實上線要另外手動、謹慎驗證的原因，這次的 mock 測試只
  驗證「程式碼收到 403/503 時的處理邏輯正確」，不驗證「104 真的會不會回 403/503」
- **[風險，已驗證並修正] list/detail fixture 原本是照 `source-api-notes.md` 文件描述
  手寫的，不是真實 API response 的原樣存檔**——2026-08-01 執行了一次極低頻（各一筆，
  list 一頁 + detail 一筆，經明確同意才打）的真實請求，把原始 JSON 存到
  `104-api-poc/samples/`，拿去對照 `Job104ListScraper`/`Job104RawPayloadParser` 的
  欄位假設，發現並修正兩個落差：
  1. 手寫 fixture 誤把 `jobNo`（upsert 用的來源 id）跟 slug（detail API 用的路徑代碼）
     設成同一個值，測試因此無法真正驗證兩者不會被搞混——已改成兩套不同格式的值
  2. `Job104RawPayloadParser` 沒有處理面議職缺：104 面議時 `salaryMin`/`salaryMax`
     回傳數字 `0`（不是 JSON `null`，跟 Yourator/CakeResume 不同），原本會把 `0`
     誤存成「時薪 0 元」——已修正成正規化為 `null`，並用真實取樣到的樣本（去除聯絡
     資訊等非必要欄位後）存成 `job104-detail-negotiable-salary.json` fixture，取代
     手寫資料
  其餘欄位名稱/巢狀結構（`header`/`jobDetail`、`addressArea`/`addressRegion`、
  `metadata.pagination`）比對後跟原本的文件記錄一致，沒有發現落差
- **[風險，已修正說明] `lastPage` 上限原本文件記成固定「100 頁」，經第二次不同
  `pagesize` 的實測發現其實是「約 3000 筆可觸及結果」（`lastPage × pagesize ≈
  3000`）**——不影響程式碼正確性（`currentPage < lastPage` 判斷本身不依賴這個數字），
  純粹是文件描述不夠精確，已在 `source-api-notes.md` 更正

## Migration Plan

- Flyway migration：無 schema 變動（`search_queries.source` 沒有 DB 層級的 CHECK
  約束，見探索階段發現，`104` 這個字串值本身不需要 migration 才能使用）
- 不 seed 種子資料（見上方 Decisions）
