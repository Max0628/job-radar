## Why

各來源的 API 端點（base URL、path）目前分散硬編碼在各自的 scraper/client 類別裡，且同一個
平台的端點在不同模組間逐字重複——例如 Yourator 的 base URL 分別寫在 `collector` 模組的
`YouratorListScraper` 跟 `api` 模組的 `YouratorFacetsClient` 裡，CakeResume 的搜尋端點
`/api/client/v1/jobs/search` 也是同樣情況。之後加入新來源時容易複製貼上出第三份，改端點
時也容易漏改其中一處而不自知。

## What Changes

- 在 `common` 模組新增每個來源的端點常數類別（`YouratorEndpoints`、
  `CakeResumeEndpoints`），集中該平台會用到的 base URL 與各支 API 的 path
- `collector` 的 `YouratorListScraper`/`CakeResumeListScraper`、`api` 的
  `YouratorFacetsClient`/`CakeResumeFacetsClient` 改讀這些共用常數，刪除各自重複宣告的
  `BASE_URL`/path 字面值
- 純內部重構，**不改變任何對外部平台的實際請求**（URL/path 字串值完全不變，只是搬到
  共用位置），不是 **BREAKING** change
- `worker` 模組的 `YouratorDetailScraper` 不在此次範圍——它沒有硬編碼任何端點，
  detail 頁網址是從 `DiscoveredJob.url()` 當參數傳入的，沒有東西可抽

## Non-Goals

- 不處理 104 這個新來源（尚未實作，之後加入時直接沿用這次建立的模式）
- 不順便統一 `YouratorFacetsClient`/`CakeResumeFacetsClient` 裡各自硬編碼、彼此重複的
  `USER_AGENT` 字串常數——那是跟這次「端點」主題不同的另一項技術債，範圍不同不應該
  這次順手一起做，留待之後視情況另開 change
- 不改變任何一個端點的實際 URL/path 值，不是功能變更

## Capabilities

### New Capabilities
- `source-endpoint-declaration`：每個來源的 API 端點（base URL、path）只能在
  `common` 模組宣告一次，`collector`/`api` 皆從該處讀取，不得各自重複宣告字面值。
  這不是使用者可觀察的行為，是工程維護性層級的系統需求（跟 D14 那條「upsert SQL
  欄位要寫全」屬於同一類——避免因為同一份事實散落多處而漏改其中一處）

### Modified Capabilities
（無——URL/path 字面值不變，`openspec/specs/` 目前也是空的，沒有既有 capability 可以
標記修改）

## Impact

- `common` 模組：新增 `YouratorEndpoints`、`CakeResumeEndpoints` 兩個常數類別
- `collector` 模組：`YouratorListScraper`、`CakeResumeListScraper` 改讀共用常數
- `api` 模組：`YouratorFacetsClient`、`CakeResumeFacetsClient` 改讀共用常數
- 不影響 `worker` 模組
- 不影響對外部平台的請求（URL 值不變）、不影響現有測試的 mock 斷言（`requestTo`/
  `content()` 斷言比對的字串值不變）
