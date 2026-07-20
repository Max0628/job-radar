# 求職平台來源 API 盤點

爬蟲 POC 階段的實測紀錄，供 collector adapter 實作參考。
curl 版本可直接貼 Postman；JSON 版本方便閱讀理解。
Response 欄位細節先不寫，由使用者自行補上。

---

## Yourator

### List / Search API

> **更正（2026-07-20）**：原本記錄的 `keyword` 參數是錯的，實測完全不生效（不管填什麼結果都一樣）。
> 用瀏覽器側錄真實請求後找到正確參數是 `term[]`，另外還有 `area[]`（地區）、`sort`。

**cURL**

```bash
curl -G "https://www.yourator.co/api/v4/jobs" \
  --data-urlencode "page=1" \
  --data-urlencode "sort=most_related" \
  --data-urlencode "term[]=後端工程師" \
  --data-urlencode "area[]=TPE" \
  -H "Accept: application/json"
```

**JSON 版本**

```
GET https://www.yourator.co/api/v4/jobs

Query params:
  page: integer        # 從 1 開始
  sort: string          # 已確認存在，前端預設 "most_related"；其他合法值未驗證
  term[]: string         # 關鍵字，陣列格式，中英文皆可（已驗證會正確過濾）
  area[]: string          # 地區代碼，陣列格式（已驗證會正確過濾）

Headers:
  Accept: application/json
```

**地區代碼查詢**（`area[]` 的合法值來源）

```bash
curl -G "https://www.yourator.co/api/v4/areas" \
  --data-urlencode "query_key[]=job_areas" \
  -H "Accept: application/json"
```

回應範例：`TPE`=臺北市、`NWT`=新北市、`TAO`=桃園市、`TXG`=臺中市、`TNN`=臺南市、`KHH`=高雄市 等。

備註：
- 每頁固定 20 筆，`hasMore` / `nextPage` 驅動分頁
- 不需要 cookie / CSRF token / 登入；瀏覽器側錄到的請求有帶 `cf_clearance`、`_yourator_session`、
  `x-csrf-token`，但拿掉這些純 server-to-server 呼叫一樣正常運作
- `sort` 除了預設的 `most_related` 還有哪些值、是否有可用來做時間游標的選項（如 latest/created_at）
  尚未驗證，直接影響能不能用 early-termination 分頁策略，待確認（見 openspec 的
  `add-walking-skeleton/design.md` 附錄）
- 無需登入、無 API key、未觀察到 rate limit；仍照禮貌規則走（同來源並發 ≤2、間隔 ≥1s、429 退避）
- `robots.txt` 只禁止 `/r/*`，`/api/` 未禁止

### Detail 頁

**cURL**

```bash
curl "https://www.yourator.co/companies/{company_slug}/jobs/{job_id}" \
  -H "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
```

**JSON 版本**

```
GET https://www.yourator.co{path}

path 從 list API 回應的 job.path 取得，例如 /companies/aifian/jobs/41246
```

**這不是「第二支 API」，`path` 也不是 API 參數**：`path` 是網站本來就要給瀏覽器導頁用的一般網址
路徑（前端用它組「查看詳情」連結），detail 端點打的就是一般人瀏覽器打開會看到的職缺頁面本身。
用瀏覽器渲染這個網址、側錄背後所有請求後**沒有找到任何 `jobs/{id}` 這類 detail JSON API**，只有
一堆語系字串包（`assets.yourator.co/lang/*.json`）跟追蹤/動態區塊呼叫——代表 detail 頁是伺服端
直接渲染好的，職缺全文在第一次拿到的 HTML 裡就有了，不需要瀏覽器執行 JS 再打第二次請求。

備註：
- 伺服端渲染 HTML（非 SPA JSON），但內嵌乾淨的 schema.org `JobPosting` JSON-LD
  （`<script type="application/ld+json">`），detail 資料直接從這裡取
- **不是「parse HTML」，是「選一個標籤」**：只需要用 CSS selector 選出
  `script[type=application/ld+json]` 這一個節點（例如 Java 用 Jsoup 的 `selectFirst`），
  不用管周圍排版的 DOM 結構、class 名稱、巢狀層級；網站改版也不影響，因為這段是給 Google
  搜尋結果讀的標準格式（SEO 用途），不是特地留給爬蟲的
- `baseSalary` 是結構化資料（minValue/maxValue/currency/unitText）
- 沒有 `dateModified` 欄位，只有 `datePosted`
- User-Agent 需帶正常瀏覽器字串

---

## CakeResume（cake.me）

> 網域已改為 `cake.me`，`www.cakeresume.com` 會 301 導向過去。

### List / Search API

**cURL**

```bash
curl -X POST "https://api.cake.me/api/client/v1/jobs/search" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Referer: https://www.cake.me/jobs" \
  -H "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" \
  -d '{"query":"後端工程師","filters":{"location":"Taipei"},"page":1,"sort_by":"latest"}'
```

**JSON 版本**

```
POST https://api.cake.me/api/client/v1/jobs/search

Headers:
  Content-Type: application/json
  Accept: application/json
  Referer: https://www.cake.me/jobs
  User-Agent: <正常瀏覽器 UA>

Body:
{
  "query": "後端工程師",       // 關鍵字，中英文皆可，職稱/自由文字
  "filters": {
    "location": "Taipei"        // 地區另外包在 filters 物件裡，不跟 query 同層
  },
  "page": 1,
  "sort_by": "latest"           // 必填，只接受 "popularity" 或 "latest"
                                  // （relevance 排序僅供有下廣告的 campaign 用，一般查詢會被拒）
}
```

**跟 Yourator 不同：這支 API 可能不需要 detail 階段。** 回應 `data` 陣列裡每筆職缺已實測含
`description` 欄位，內容是完整職缺全文（實測一筆 2155 字元，結尾是完整句子，非截斷），另外還有
`salary`、`seniority_level`、`job_type`、`min_work_exp_year`、`content_updated_at`、`page`
（公司資訊）等欄位——這些在 Yourator 得另外打 detail 頁才拿得到的東西，這裡一次 search 就給了。
**尚未驗證**的是這是否等於完整頁面內容（還沒找一個真實職缺頁面逐欄比對），先記錄現況、
adapter 設計時列為待確認項。

### 篩選維度（`available_facets`，回應裡附的清單）

回應 body 的 `available_facets` 欄位列出這個 API 支援的所有篩選面向與可選值。
以下每個欄位都已**實測驗證**：對 `後端工程師` + `Taipei` 的基準查詢（`total_entries=69`）
逐一加上該 filter key，觀察 `total_entries` 是否隨之變化來確認是否生效
（陣列型別，例如 `"job_types": ["full_time"]`）。

| filters key | 可選值範例 | 驗證結果 |
|---|---|---|
| `location` | `Taiwan`/`台灣`、`Taipei City, Taiwan`/`台北市, 台灣`、`New Taipei, Taiwan` 等 51 種（對應 facet 的 `locations`） | **有效**（基準測試本身即用此欄位） |
| `professions`（職缺分類） | `it_back-end-engineer`、`it_full-stack-development`、`it_front-end-engineer`、`it_python-developer`、`it_app-developer` 等 18 種 | **有效**（`query` 留空、加 `professions:["it_back-end-engineer"]` → 171 筆） |
| `job_types`（工作型態） | `full_time`、`contract`、`internship` | **有效**（加 `job_types:["full_time"]` → 67 筆，69→67） |
| `seniority_levels`（資歷） | `mid_senior_level`、`entry_level`、`associate`、`director` | **有效**（加 `seniority_levels:["entry_level"]` → 16 筆） |
| `remote`（遠端） | `no_remote_work`、`partial_remote_work`、`optional_remote_work`、`full_remote_work` | **有效**（加 `remote:["full_remote_work"]` → 2 筆） |
| `year_of_seniority`（年資區間） | `0_1`、`1_3`、`3_5`、`5_10` | **有效**（加 `year_of_seniority:["0_1"]` → 6 筆） |
| `number_of_management`（帶人經驗） | `none`、`one_five`、`five_ten` | **有效**（加 `number_of_management:["none"]` → 67 筆） |
| `lang_names`（語言要求） | Chinese、English、Japanese | **有效**（加 `lang_names:["English"]` → 17 筆） |
| `salary` | `currency`: TWD/JPY/THB、`type`: per_month/per_year/per_hour、`max: 12000000` | 未測，物件型別（非陣列），塞法待確認 |
| `tech_labels`（技能標籤，直接塞在 `filters` 底下） | `python`、`kubernetes`、`docker`、`terraform`、`kafka`、`ansible` 等 | **確認無效**——直接塞 `filters.tech_labels` 會被忽略（`total_entries` 沒變化，回傳值跟不加這個 key 的對照組一樣是 10000 上限），正確路徑未知 |
| `sectors`（公司產業，直接塞在 `filters` 底下） | `tech_saas-cloud-services`、`tech_cyber-security`、`tech_software` 等 | **確認無效**——同上，直接塞會被忽略 |
| `inclusivity_traits`（友善標籤） | `foreign_talents`、`career_change`、`gender_equity`、`lgbtq` 等 7 種 | 未測 |

備註：`tech_labels`/`sectors` 在 `available_facets` 回應裡是包在一個叫 `page` 的子物件下
（`available_facets.page.tech_labels`、`available_facets.page.sectors`），這個巢狀結構暗示
它們可能不是職缺搜尋 `filters` 的欄位，而是「公司頁面」（page）維度的篩選，要另外找對應的
公司搜尋 API 或不同的 filters 路徑，這裡先記錄現況、不繼續猜測 key 名稱。

備註：
- 必須是 **POST + JSON body**，GET + query string 會被路由誤判成查單一職缺，回 404
  `{"msg":"The job is not found"}`
- 缺欄位會回 422，錯誤訊息會直接點出缺哪個欄位（試出參數形狀的方式：
  先缺 `filters` → 缺 `filters` 內的必要欄位 → 缺 `sort_by`）
- `available_facets.professions` 回應裡有分類代碼（如 `it_back-end-engineer`、
  `it_full-stack-development`），可能比自由文字關鍵字更精準，值得後續評估是否改用分類查詢
- 頁面本身是 Next.js CSR，`__NEXT_DATA__` 裡沒有職缺資料，必須呼叫此 API 或渲染後爬 DOM
- 沒有遇到 Cloudflare 或其他人機驗證；plain fetch 與 API 呼叫皆正常回應

---

## 104（暫緩，僅記錄現況）

- 前端頁面觀察到的內部端點：`GET https://www.104.com.tw/jobs/search/list`
  （`keyword`/`area`/`page`/`order`/`asc`/`mode`/`jobsource` 等參數）
- **整個網域掛 Cloudflare Turnstile**，plain HTTP request（curl / Java HttpClient）打
  API 端點與一般網頁都回 403 + 「Just a moment...」挑戰頁，非 JS 引擎無法通過
- 官方開發者中心（`developers.104.com.tw`）與 `ehr.104.com.tw` 的 API 皆為 **B2B 企業端**
  （履歷傳輸、職缺刊登服務），需簽約成為合作夥伴，服務方向是「企業推資料進 104」，
  不是「第三方查詢職缺列表」——沒有官方公開的職缺搜尋 API
- 現況：暫緩，不評估瀏覽器自動化繞過 Cloudflare
