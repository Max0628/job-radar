## Context

`collector`（list scraper）跟 `api`（facets client）兩個模組各自實作了同一批來源的 HTTP
呼叫，端點字面值目前各自宣告，沒有共用來源。三個 boot jar（`collector`/`worker`/`api`）
彼此不互相依賴（見 `docs/architecture.md` D7），只共同依賴 `common` 模組，因此共用常數
只能放在 `common`。

## Goals / Non-Goals

**Goals:**
- 每個來源的 base URL/path 只宣告一次，`collector`/`api` 都從 `common` 讀
- 不改變任何端點的實際字串值

**Non-Goals:**
- 不處理 `USER_AGENT` 字串重複（見 proposal.md Non-Goals）
- 不處理 104（尚未實作）
- 不引入設定檔（`.properties`/`.yml`）——這些是結構性事實，不是需要依環境調整的操作參數，
  跟 D23 那批重試/逾時常數的性質不同，直接用 Java 常數類別更合適

## Decisions

**用純 Java 常數類別，不用 `@ConfigurationProperties`**
端點 URL 不是「不同環境可能要改的操作參數」（不像 D23 的 retry/timeout，那些合理會依
環境調整），是平台整合本身的結構性事實——寫死在程式碼裡、跟著程式碼一起版本控制，
比放進 `application.yml` 更直接，也不會有「改錯環境變數、打錯網站」這種設定檔特有的
風險類別。
- 被否決：`@ConfigurationProperties` + `application.yml`——這些值幾乎不會需要在不進版控
  的情況下改動，放進設定檔只是多一層間接、沒有實質彈性收益

**每個來源一個常數類別，不做成單一巨大的 `Endpoints` 類別**
`YouratorEndpoints`/`CakeResumeEndpoints` 各自獨立，之後加 104 直接新增
`Job104Endpoints`，不會因為一個來源的端點變動牽動到不相關來源的檔案。
- 被否決：單一 `SourceEndpoints` 類別裡用 nested class 或 `Map<String, String>` 放所有
  來源——104 之後可能有 list/detail 兩種端點（跟 Yourator 的結構類似但語意不同），分開
  的類別比共用一個大結構更容易各自演化

**類別放在 `common` 的 `dev.jobradar.common.source` 套件下（新套件）**
`common` 目前的套件是 `domain`/`envelope`/`kafka` 這幾個，端點常數不屬於任何一個既有
分類，開一個新的 `source` 套件承接，之後 104 的端點類別也放這裡。

## Risks / Trade-offs

- **[風險] 常數類別是純字串，沒有型別層級防呆（例如 path 打錯字不會編譯期發現）**
  → 緩解：跟現況比沒有變差（現況也是純字串常數），且既有測試的 `requestTo`/`content()`
  斷言會在字串值錯誤時直接讓測試失敗，等同執行期防呆
