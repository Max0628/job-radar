"""104 人力銀行 POC —— 驗證公開 JSON 接口能不能拿到搜尋結果 + 職缺詳情。"""

from __future__ import annotations

from scrapling.fetchers import Fetcher

from common import USER_AGENT, save_json, sleep_politely

BASE = "https://www.104.com.tw"
SEARCH_URL = f"{BASE}/jobs/search/list"
DETAIL_URL_TMPL = f"{BASE}/job/ajax/content/{{job_no}}"


def _search_page(keyword: str, page: int, area: str | None, order: int, asc: int):
    params = {
        "keyword": keyword,
        "page": page,
        "order": order,
        "asc": asc,
        "mode": "s",
        "jobsource": "2018indexpoc",
    }
    if area:
        params["area"] = area
    headers = {
        "Referer": f"{BASE}/jobs/search/",
        "User-Agent": USER_AGENT,
        "Accept": "application/json",
    }
    resp = Fetcher.get(
        SEARCH_URL, headers=headers, params=params, retries=3, retry_delay=5, timeout=15
    )
    if resp.status != 200:
        raise RuntimeError(f"104 search failed: status={resp.status} page={page}")
    return resp.json()


def _fetch_detail(job_no: str):
    url = DETAIL_URL_TMPL.format(job_no=job_no)
    headers = {
        "Referer": f"{BASE}/job/{job_no}",
        "User-Agent": USER_AGENT,
        "Accept": "application/json",
    }
    resp = Fetcher.get(url, headers=headers, retries=3, retry_delay=5, timeout=15)
    if resp.status != 200:
        raise RuntimeError(f"104 detail failed: status={resp.status} job_no={job_no}")
    return resp.json()


def _map_job(item: dict, detail: dict | None) -> dict:
    job_no = item.get("jobNo")
    job = {
        "source": "104",
        "job_id": job_no,
        "title": item.get("jobName"),
        "company": item.get("custName"),
        "location": item.get("jobAddrNoDesc"),
        "salary": item.get("salaryDesc"),
        "description": item.get("description"),
        "requirements": [],
        "url": f"{BASE}/job/{job_no}",
        "posted_at": item.get("appearDate", ""),
    }
    if detail:
        data = detail.get("data", {})
        job_detail = data.get("jobDetail", {})
        condition = data.get("condition", {})
        if job_detail.get("jobDescription"):
            job["description"] = job_detail["jobDescription"]
        # NOTE: 欄位名稱是猜測，實際 key 要對照 output/104_detail_sample.json 校正
        for key in ("specialty", "major", "skill"):
            val = condition.get(key)
            if val:
                job["requirements"].append(str(val))
    return job


def fetch_jobs(
    keyword: str,
    area: str | None = None,
    pages: int = 1,
    order: int = 16,
    asc: int = 0,
    with_detail: bool = False,
) -> list[dict]:
    jobs: list[dict] = []
    raw_list_samples = []
    raw_detail_sample = None

    for page in range(1, pages + 1):
        print(f"[104] fetching search page {page} ...")
        payload = _search_page(keyword, page, area, order, asc)
        raw_list_samples.append(payload)

        items = payload.get("data", {}).get("list", [])
        if not items:
            print(f"[104] page {page} empty, stop pagination")
            break

        for item in items:
            detail = None
            if with_detail:
                sleep_politely()
                job_no = item.get("jobNo")
                try:
                    detail = _fetch_detail(job_no)
                    if raw_detail_sample is None:
                        raw_detail_sample = detail
                except RuntimeError as e:
                    print(f"[104] detail fetch skipped: {e}")
            jobs.append(_map_job(item, detail))

        sleep_politely()

    save_json(f"104_list_raw_{keyword}.json", raw_list_samples)
    if raw_detail_sample:
        save_json("104_detail_sample.json", raw_detail_sample)
    save_json(f"104_jobs_{keyword}.json", jobs)

    print(f"[104] done: {len(jobs)} jobs mapped, raw samples saved under output/")
    return jobs
