# Spec: job-location-extraction

從平台原始 payload 抽取地區資訊到 `jobs` 表的可查詢欄位（`district`/`city`），
供 job-browse-api 篩選使用。

抽取邏輯已用真實 API 回應查證修正（見 add-job-dashboard/design.md D3），本文件反映查證後的結論。

## ADDED Requirements

### Requirement: Yourator payload 抽取地區欄位

系統 SHALL 從 Yourator 的 JobPosting JSON-LD 抽取地區資訊。`jobLocation.address.addressLocality`
固定是縣市層級，沒有結構化的區級欄位；區級資訊只能從 `streetAddress` 這個自由文字欄位嘗試解析，
且區的位置在字串中不固定（可能在最前面、可能在縣市名稱之後），為 best-effort。

#### Scenario: 抽取城市層級
- **WHEN** YouratorRawPayloadParser 解析含 `jobLocation.address.addressLocality` 的 payload
- **THEN** NormalizedJob.city 設為該值，並正規化為「台」通用字（如「臺北市」轉成「台北市」，
  見下方城市名稱正規化 Requirement）

#### Scenario: 從 streetAddress 嘗試抽取區級資訊
- **WHEN** payload 的 `jobLocation.address.streetAddress` 包含符合「[中文]{2,3}區」pattern
  的子字串（不論位置在字串開頭、中間或結尾）
- **THEN** NormalizedJob.district 設為抓到的第一個符合 pattern 的字串
- **AND** 若 `streetAddress` 不存在或找不到符合 pattern 的子字串，district 留 null

#### Scenario: 無地區資料時留 null
- **WHEN** payload 沒有 `jobLocation` 欄位
- **THEN** NormalizedJob.city 與 district 皆為 null，不拋例外

### Requirement: CakeResume payload 抽取地區欄位

系統 SHALL 從 CakeResume 的 `locations` 陣列抽取地區資訊。`locations[0]` 的逗號分隔段數
不固定，依地區精確度而變（3 段「區, 市, 國」、2 段「市, 國」、1 段純國家名），解析邏輯
必須先判斷段數，不能假設固定位置。

#### Scenario: 3 段格式（區, 市, 國）
- **WHEN** CakeResumeRawPayloadParser 解析 `locations: ["中山區, 台北市, 台灣"]`
  （逗號切分後恰好 3 段）
- **THEN** NormalizedJob.district 設為「中山區」，city 設為「台北市」（第一段為 district、
  第二段為 city）

#### Scenario: 2 段格式（市, 國，無區級資訊）
- **WHEN** CakeResumeRawPayloadParser 解析 `locations: ["Taipei City, Taiwan"]`
  （逗號切分後恰好 2 段）
- **THEN** NormalizedJob.city 設為第一段（「Taipei City」），district 留 null
- **COMMENT** 這是對先前版本 spec 的修正——先前假設「固定第一段是 district、第二段是
  city」，這在 2 段情況下是錯的，會把 city 誤存成 district

#### Scenario: 1 段格式（純國家名）或格式不符預期
- **WHEN** `locations[0]` 逗號切分後只有 1 段，或 `locations` 為空陣列/不存在
- **THEN** NormalizedJob.district 與 city 皆為 null，不拋例外（不猜測型別不明的資料）

### Requirement: 縣市名稱正規化（臺/台字元統一）

系統 SHALL 將 Yourator 抽取到的城市名稱正規化，統一使用「台」（通用字），不使用
Yourator 原始回應的「臺」（異體字）。

#### Scenario: Yourator 的「臺北市」正規化為「台北市」
- **WHEN** YouratorRawPayloadParser 從 `addressLocality` 抽到「臺北市」
- **THEN** 寫入 NormalizedJob.city 前先做 `臺` → `台` 字元替換，儲存值為「台北市」
- **COMMENT** 兩平台的縣市名稱寫法不一致（Yourator 用「臺」、CakeResume 用「台」），
  若不正規化，跨平台的地區篩選會因為字串不相等而漏資料，見 design.md D3

#### Scenario: CakeResume 不需要正規化
- **WHEN** CakeResumeRawPayloadParser 抽取地區資訊
- **THEN** 不做字元替換（CakeResume 原始回應已經是「台」通用字）

### Requirement: jobs 表儲存地區欄位

系統 SHALL 在 `jobs` 表新增 `district`、`city` 欄位（皆可 null），並在 upsert 時寫入。

#### Scenario: upsert 寫入地區欄位
- **WHEN** JobRepository.upsert() 收到含 district/city 的 NormalizedJob
- **THEN** INSERT 與 ON CONFLICT UPDATE 皆寫入這兩個欄位
