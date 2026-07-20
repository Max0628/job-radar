"""CakeResume POC —— 先探索資料來源，才決定 fetch_jobs() 怎麼實作。

策略分層（由輕到重，不預設跳到最重的手段）：
  1. Fetcher.get 直接打頁面，看是不是已經是渲染好的 HTML（很多 SPA 首屏其實有 SSR）
  2. DynamicFetcher 渲染頁面 + capture_xhr 側錄 XHR/fetch（等同開 devtools 看 Network，
     只是自動化紀錄下來）——這只是「像瀏覽器一樣執行 JS」，不是繞過存取限制
  3. 如果 2 被擋（403 / challenge page），才印警告建議手動評估 StealthyFetcher，
     不在這裡自動加上 solve_cloudflare / hide_canvas 等反偵測參數
"""

from __future__ import annotations

from urllib.parse import quote

from scrapling.fetchers import DynamicFetcher, Fetcher

from common import USER_AGENT, save_json, sleep_politely

BASE = "https://www.cake.me"

# 可能是 API 的線索：路徑或 host 長這樣就值得懷疑
_API_HINTS = ("graphql", "/api/", "algolia", "search", "/jobs.json", "elastic")


def _build_search_url(keyword: str, location: str | None) -> str:
    url = f"{BASE}/jobs?query={quote(keyword)}"
    if location:
        url += f"&location={quote(location)}"
    return url


def discover_source(keyword: str = "devops", location: str | None = None, show_browser: bool = False) -> dict:
    url = _build_search_url(keyword, location)
    result = {"url": url, "plain_fetch": None, "rendered": None, "candidate_apis": []}

    # 1) 先試最輕量的方式
    print(f"[cake] plain GET {url}")
    plain = Fetcher.get(url, headers={"User-Agent": USER_AGENT}, timeout=15, retries=2, retry_delay=3)
    result["plain_fetch"] = {"status": plain.status, "content_length": len(plain.body or b"")}
    save_json("cake_plain_fetch_sample.json", {"status": plain.status, "html_snippet": plain.text[:3000] if plain.text else None})

    sleep_politely()

    # 2) 渲染頁面 + 側錄 XHR
    print(f"[cake] rendering with DynamicFetcher (headless={not show_browser}) ...")
    rendered = DynamicFetcher.fetch(
        url,
        headless=not show_browser,
        network_idle=True,
        capture_xhr=".*",  # regex，比對 XHR/fetch response URL；".*" = 全抓
        timeout=30000,  # ms —— DynamicFetcher/StealthyFetcher 的 timeout 單位跟 Fetcher.get(秒) 不一樣
    )
    result["rendered"] = {"status": rendered.status}

    if rendered.status in (403, 429) or rendered.status >= 500:
        print(
            f"[cake] WARNING: rendered fetch got status={rendered.status}，"
            "可能被擋。這裡不會自動切換成 StealthyFetcher 的反偵測參數，"
            "先確認是暫時性錯誤還是真的被 block，再決定要不要手動升級策略。"
        )

    xhr_records = []
    for xhr in getattr(rendered, "captured_xhr", []) or []:
        record = {
            "url": xhr.url,
            "status": xhr.status,
            "body_snippet": (xhr.text[:2000] if xhr.text else None),
        }
        xhr_records.append(record)
        if any(hint in xhr.url.lower() for hint in _API_HINTS):
            result["candidate_apis"].append({"url": xhr.url, "status": xhr.status})

    save_json("cake_xhr_capture.json", xhr_records)
    save_json("cake_rendered_dom_sample.html.json", {"html_snippet": rendered.text[:5000] if rendered.text else None})

    print(f"[cake] captured {len(xhr_records)} xhr/fetch requests, "
          f"{len(result['candidate_apis'])} look like APIs (see output/cake_xhr_capture.json)")
    for cand in result["candidate_apis"]:
        print(f"  candidate: {cand['url']} (status={cand['status']})")

    save_json("cake_discover_summary.json", result)
    return result


def fetch_jobs(keyword: str, location: str | None = None) -> list[dict]:
    """先跑 discover_source() 看 output/cake_discover_summary.json 有沒有 candidate_apis。

    - 有找到像 API 的 endpoint：把它加進這裡，用 Fetcher.get/post 直接呼叫，
      解析後對應到統一格式（source/job_id/title/...），可以參照 job104_poc.py 的 _map_job 寫法。
    - 沒找到（純 DOM 渲染）：改用 DynamicFetcher.fetch(..., capture_xhr=False) 拿 rendered.css(...)
      解析職缺卡片，selector 要照 output/cake_rendered_dom_sample.html.json 的實際結構填。
    """
    raise NotImplementedError(
        "先呼叫 discover_source() 並檢查 output/cake_discover_summary.json，"
        "確認資料來源形狀後再實作這個函式"
    )
