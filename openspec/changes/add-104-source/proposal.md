## Why

`docs/architecture.md` D22/D24 與 `docs/source-api-notes.md` 的 104 章節已經完成規格
盤點與架構設計（API 規格、語言選型 Java、前端整合方式），但 job-radar 裡沒有任何一行
104 相關程式碼。這個 change 把 104 補成第三個正式來源，直接套用前兩個 change
（`add-parallel-scan-scheduling`、`add-source-error-alerting`）已經建好的排程模型與
錯誤分類機制，不用重新設計。

## What Changes

- `common` 模組新增 `Job104Endpoints`（list/detail API + `static.104.com.tw` 靜態
  參考資料端點），比照 `add-shared-source-endpoints` 的模式
- `collector` 新增 `Job104ListScraper implements JobListScraper`：`needsDetail=true`，
  用 `metadata.pagination.currentPage < lastPage` 判斷分頁，套用三類錯誤分類（見
  `add-source-error-alerting`），並發/jitter/重試值走 `CollectorScanProperties.sources`
  覆寫（比 Yourator/CakeResume 更保守）
- `CollectorScanProperties` 新增「per-source 請求間隔隨機區間」覆寫欄位（104 需要
  3–10 秒隨機 jitter，現有的 `requestIntervalMillis` 是全域單一值、沒有覆寫機制）
- `worker` 新增 `Job104DetailScraper implements DetailScraper`（乾淨 JSON API，不用
  Jsoup）與 `Job104RawPayloadParser implements RawPayloadParser`
- `api` 新增 `Job104FacetsClient implements FacetsClient`（讀 `static.104.com.tw` 的
  `Area.json`/`JobCat.json`，`JobCat.json` 需要遞迴攤平巢狀分類樹）
- `SearchQueryRepository.registeredSources()` 加入 `"104"`
- `frontend`：`SearchQueryForm.tsx`/`JobList.tsx` 的 `SOURCE_CHOICES`、
  `types/index.ts` 型別、`CategoryAndLocationInputs` 新增 104 分支
- **不新增** `search_queries` 種子資料——104 第一次掃描等同一次深掃（見
  `docs/architecture.md`「首次掃描的特殊性」），刻意留給使用者事後手動用窄範圍設定
  啟用，不隨 migration 自動開始掃描

## Non-Goals

- 不對真實 104 網站做端到端驗證——這次全程用 fixture mock（內容取自這次規格盤點階段
  實測過的真實回應），真實上線驗證是另一個手動、單次、事後才做的步驟（見
  `docs/architecture.md` D20）
- 不處理 `order`/「更新時間」篩選的精確語意（`source-api-notes.md` 已記錄為待確認、
  不影響上線）
- 不做 Area.json 反查 district——用字串處理簡化（見 design.md）

## Capabilities

### New Capabilities
- `job-104-source`：涵蓋 104 的 list/detail 抓取、資料正規化、分類/地區選單整合

### Modified Capabilities
（無——是新增一個來源，不改變既有 `scan-scheduling`/`source-blocked-detection`/
`scan-summary-reporting` 的既有需求，104 直接遵守它們）

## Impact

- `common`：`Job104Endpoints`、`CollectorScanProperties` 擴充
- `collector`：`Job104ListScraper`
- `worker`：`Job104DetailScraper`、`Job104RawPayloadParser`
- `api`：`Job104FacetsClient`、`SearchQueryRepository`
- `frontend`：`SearchQueryForm.tsx`、`JobList.tsx`、`types/index.ts`
- 不影響既有的 Yourator/CakeResume 程式碼本身（只有 `CollectorScanProperties` 這個
  共用設定類別會被擴充欄位，既有欄位/行為不變）
