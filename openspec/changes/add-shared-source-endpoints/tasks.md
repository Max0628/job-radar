## 1. 建立共用端點常數（common 模組）

- [x] 1.1 新增 `common/src/main/java/dev/jobradar/common/source/YouratorEndpoints.java`：
      `BASE_URL`、`JOBS_LIST_PATH`（`/api/v4/jobs/`）、`JOB_CATEGORIES_PATH`
      （`/api/v4/job_categories`）、`AREAS_PATH`（`/api/v4/areas`）
- [x] 1.2 新增 `common/src/main/java/dev/jobradar/common/source/CakeResumeEndpoints.java`：
      `BASE_URL`、`SEARCH_PATH`（`/api/client/v1/jobs/search`，list scraper 跟 facets
      client 共用同一個端點）

## 2. collector 模組改讀共用常數

- [x] 2.1 `YouratorListScraper`：`BASE_URL` 改讀 `YouratorEndpoints.BASE_URL`，
      `uriBuilder.path("/api/v4/jobs/")` 改讀 `YouratorEndpoints.JOBS_LIST_PATH`
- [x] 2.2 `CakeResumeListScraper`：`BASE_URL` 改讀 `CakeResumeEndpoints.BASE_URL`，
      `.uri("/api/client/v1/jobs/search")` 改讀 `CakeResumeEndpoints.SEARCH_PATH`

## 3. api 模組改讀共用常數

- [x] 3.1 `YouratorFacetsClient`：`BASE_URL` 改讀 `YouratorEndpoints.BASE_URL`，
      `/api/v4/job_categories`/`/api/v4/areas` 改讀對應常數
- [x] 3.2 `CakeResumeFacetsClient`：`BASE_URL` 改讀 `CakeResumeEndpoints.BASE_URL`，
      `/api/client/v1/jobs/search` 改讀 `CakeResumeEndpoints.SEARCH_PATH`

## 4. 驗證

- [x] 4.1 `./gradlew :collector:test` 全過，確認 URL 值不變、既有 mock 斷言不受影響
- [x] 4.2 `./gradlew :api:test` 全過——`YouratorFacetsClientTest`/
      `CakeResumeFacetsClientTest` 既有測試都在，皆通過（不用另外新增）
- [x] 4.3 `./gradlew build` 全模組編譯過（`common`/`collector`/`worker`/`api` 四個
      boot jar 皆建置成功）
