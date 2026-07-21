# Spec: per-source-normalizer (delta)

延伸自 `add-multi-source-cakeresume` 的 per-source-normalizer 能力，
新增地區欄位抽取（見 job-location-extraction 能力）。

## MODIFIED Requirements

### Requirement: Normalizer 能路由到 per-source parser

系統 SHALL 根據 RawEnvelope 的 source 欄位，路由到對應的 RawPayloadParser，將原始 payload 轉成 NormalizedJob。

#### Scenario: Yourator 職缺路由到 YouratorRawPayloadParser
- **WHEN** Normalizer 消費一筆 RawEnvelope，source = "yourator"
- **THEN** 路由到 YouratorRawPayloadParser
- **AND** Parser 從 JSON-LD payload 提取：
  - title（來自 title 欄位）
  - company（來自 hiringOrganization.name）
  - salaryMin / salaryMax（來自 baseSalary.value.minValue/maxValue，若不存在則 null）
  - salaryCurrency（來自 baseSalary.currency）
  - description（來自 description，HTML 格式）
  - url（來自 RawEnvelope.url）
  - employment_type（來自 employmentType）
  - **city（來自 jobLocation.address.addressLocality，正規化為「台」通用字後存入，
    若不存在則 null，見 job-location-extraction 能力）**
  - **district（best-effort：從 jobLocation.address.streetAddress 用正則抓
    「[中文]{2,3}區」pattern，抓不到則為 null，見 job-location-extraction 能力）**
- **AND** 回傳 NormalizedJob 物件

#### Scenario: CakeResume 職缺路由到 CakeResumeRawPayloadParser
- **WHEN** Normalizer 消費一筆 RawEnvelope，source = "cakeresume"
- **THEN** 路由到 CakeResumeRawPayloadParser
- **AND** Parser 從 search result payload 提取：
  - title
  - company（來自 page.name）
  - salaryMin / salaryMax（來自 salary.min/max，若不存在則 null）
  - salaryCurrency（來自 salary.currency）
  - description（來自 description）
  - url（組合 path 成 https://www.cake.me/jobs/{path}）
  - job_type（來自 job_type，例 full_time）
  - seniority_level（來自 seniority_level，例 mid_senior_level）
  - lang_name（來自 lang_name）
  - min_work_exp_year（來自 min_work_exp_year）
  - **district、city（來自 locations 陣列第一個字串，先判斷逗號切分後的段數：
    3 段「區,市,國」→ district=第一段、city=第二段；2 段「市,國」→ city=第一段、
    district=null；其餘情況兩者皆留 null。見 job-location-extraction 能力，
    這裡修正了先前版本「固定第一段是 district」的錯誤假設）**
- **AND** 回傳 NormalizedJob 物件

#### Scenario: 未知來源採用預設處理
- **WHEN** Normalizer 消費 RawEnvelope，source 不在已註冊的 parser 清單
- **THEN** 發送告警／DLQ 訊息，不進行 upsert
