# poc-scrapling —— 一次性可行性驗證，非正式程式碼

這個資料夾**不是** job-radar 系統的一部分，跟 Gradle multi-module（`collector`/`worker`/`api`）無關，
不會被 CI 建置，也不打算長期維護。目的只有一個：

> 驗證 104、CakeResume 能不能拿到職缺資料、資料長什麼樣子，
> 把結論寫回 `docs/architecture.md` 的 002 change（或 CakeResume 對應的 milestone），
> 之後正式的 adapter 一律用 Java 實作進 `collector`/`worker`。

背景討論見對話紀錄：D1（Java 21 語言決策）、D7（3 個 deployable unit）並未被推翻，
這裡的 Python + Scrapling 只是探勘工具。

## 環境

```bash
uv venv .venv
uv pip install --python .venv/bin/python "scrapling[fetchers]"
.venv/bin/scrapling install   # 下載 playwright 瀏覽器（DynamicFetcher/StealthyFetcher 才需要）
```

## 用法

```bash
# 104：關鍵字搜尋 + 抓 detail
.venv/bin/python main.py job104 --keyword "devops" --area 6001001000 --pages 2 --with-detail

# CakeResume：探索資料來源（會開瀏覽器側錄 XHR/fetch）
.venv/bin/python main.py cake-discover --keyword devops --show-browser

# CakeResume：探索完、確認 API 形狀後才會有 fetch_jobs()
.venv/bin/python main.py cake-fetch --keyword devops
```

輸出都存在 `output/`，方便直接打開檢查欄位長相。

## 禮貌規則（跟 CLAUDE.md 一致，POC 也遵守）

- 同來源不併發、每個請求間隔 2–5 秒隨機
- 遇 429 / 5xx 用內建 retries 退避，不狂打
- CakeResume 一律先試「正常請求」「渲染頁面側錄 XHR」，**不預設**開反偵測的
  stealth cloaking（`solve_cloudflare`/`hide_canvas`/`block_webrtc` 等）。
  如果真的被擋，這裡只印警告、不自動加碼繞過，由你自己決定要不要升級策略。
