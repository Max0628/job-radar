## 1. common：端點與設定擴充

- [x] 1.1 新增 `common/.../source/Job104Endpoints.java`：`LIST_BASE_URL`
      （`https://www.104.com.tw`）、`LIST_PATH`（`/jobs/search/api/jobs`）、
      `DETAIL_BASE_URL`（同上）、`DETAIL_PATH_TEMPLATE`（`/api/jobs/%s`）、
      `STATIC_BASE_URL`（`https://static.104.com.tw`）、`AREA_JSON_PATH`、
      `JOB_CAT_JSON_PATH`
- [x] 1.2 `CollectorScanProperties.SourceOverrides` 新增
      `requestIntervalMinMillis`/`requestIntervalMaxMillis`（皆為 `Long`），新增
      resolver method 算隨機區間值；`application.yml` 補上 104 的 `sources` 覆寫區塊
      （並發相關的既有機制不用改，個別來源本來就是各自的 `fetchPage` 迴圈序列執行）

## 2. collector：Job104ListScraper

- [x] 2.1 `Job104ListScraper implements JobListScraper`：`needsDetail=true`，
      分頁判斷用 `metadata.pagination.currentPage < lastPage`，錯誤分類套用
      Forbidden/ServiceUnavailable 不重試分支（比照 Yourator/CakeResume）
- [x] 2.2 `sourceJobId` 用 `jobNo`，`detailUrl` 從 `link.job` 取路徑結尾 slug
- [x] 2.3 fixture：用 `source-api-notes.md`/POC 階段記錄的真實回應內容做
      `104-list-page1.json` 等測試 fixture
- [x] 2.4 `Job104ListScraperTest`：比照 `YouratorListScraperTest`/
      `CakeResumeListScraperTest` 的完整測試矩陣（正常分頁、分頁卡住、429 重試、
      逾時重試、403/503 不重試、早停、深掃接續）

## 3. worker：Job104DetailScraper + Job104RawPayloadParser

- [x] 3.1 `Job104DetailScraper implements DetailScraper`：`GET
      Job104Endpoints.DETAIL_PATH_TEMPLATE` 格式化 slug，套用跟 list scraper 一致的
      三類錯誤分類（不用 `@Retry` 註解，見 design.md）
- [x] 3.2 `Job104RawPayloadParser implements RawPayloadParser`：依
      `source-api-notes.md` 欄位對照表映射，`district` 用字串前綴去除法，
      `salaryCurrency` 固定 `"TWD"`，`postedAt` 用自訂 `DateTimeFormatter` 解析
      `"2026/07/31"` 格式
- [x] 3.3 fixture：`104-detail.json`（用 POC 階段實測過的 Garena/QA 職缺回應內容）
- [x] 3.4 `Job104DetailScraperTest`、`Job104RawPayloadParserTest`（比照 Yourator
      對應測試的模式：前者 mock HTTP，後者純 `ObjectMapper.readTree` 單元測試）

## 4. api：Job104FacetsClient

- [x] 4.1 `Job104FacetsClient implements FacetsClient`：遞迴攤平 JobCat.json 巢狀樹，
      Area.json 同樣處理（雖然目前是巢狀到區級，跟 JobCat 用同一套遞迴邏輯）
- [x] 4.2 fixture：JobCat.json/Area.json 的節錄版本（不用整份 681 節點，取有代表性
      的子集）
- [x] 4.3 `Job104FacetsClientTest`（比照 `YouratorFacetsClientTest`）

## 5. 註冊與前端

- [x] 5.1 `SearchQueryRepository.registeredSources()` 加入 `"104"`
- [x] 5.2 `frontend/src/resources/searchQueries/SearchQueryForm.tsx`：
      `SOURCE_CHOICES` 加 104，`CategoryAndLocationInputs` 加 104 分支
- [x] 5.3 `frontend/src/resources/jobs/JobList.tsx`：`SOURCE_CHOICES` 加 104
- [x] 5.4 `frontend/src/types/index.ts`：`SearchQuery.source` 型別加 `"104"`

## 6. 驗證

- [x] 6.1 `./gradlew :collector:test`、`:worker:test`、`:api:test` 全過
- [x] 6.2 `./gradlew build` 全模組編譯過
- [x] 6.3 前端：確認 TypeScript 型別檢查過（若有既有的 lint/typecheck 指令）
- [x] 6.4 2026-08-01 補做：極低頻（各一筆，經明確同意）打真實 104 list/detail API，
      拿真實回應核對 fixture/欄位假設，修正兩個落差（jobNo/slug 誤設成同值、面議
      職缺 `salaryMin`/`salaryMax=0` 未正規化），詳見 design.md Risks

## 7. 疑似封鎖自動停用（2026-08-01 新增）

- [x] 7.1 `common`：新增 `SourceBlockedException`；`SearchQuery` record 新增
      `disabledReason` 欄位；V13 migration 新增 `search_queries.disabled_reason`
- [x] 7.2 `collector`：`Job104ListScraper` 403/503 改拋 `SourceBlockedException`；
      `SearchQueryRepository.disableAllForSource()`；`ScanService` 專門攔截並呼叫
- [x] 7.3 `worker`：`Job104DetailScraper` 403/503 改拋 `SourceBlockedException`；
      新增 `SearchQueryDisableRepository`；`DetailFetcherListener` 攔截並呼叫後
      重新拋出；`KafkaConsumerConfig` 把該例外註冊成不重試、直接進 DLQ
- [x] 7.4 `api`：`SearchQueryRepository` 讀寫 `disabled_reason`，重新啟用
      （`enabled=true`）時自動清空
- [x] 7.5 前端：`SearchQueryList.tsx` 顯示「停用原因」欄位，`types/index.ts` 加
      `disabledReason`
- [x] 7.6 測試：`ScanServiceTest`/`Job104ListScraperTest`/`Job104DetailScraperTest`/
      新增 `DetailFetcherListenerTest`/`SearchQueryRepositoryTest`（api，
      `requires-docker`，寫了但這個環境不能跑，如實記錄）全部涵蓋；
      `./gradlew build -PskipDockerTests` 全模組過；前端 `npm run type-check` 過
