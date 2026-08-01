import argparse
import json

import cakeresume_discover


def main():
    parser = argparse.ArgumentParser(description="job-radar POC：驗證 CakeResume 資料可行性")
    sub = parser.add_subparsers(dest="cmd", required=True)

    pcake_discover = sub.add_parser("cake-discover", help="探索 CakeResume 資料來源")
    pcake_discover.add_argument("--keyword", default="devops")
    pcake_discover.add_argument("--location", default=None)
    pcake_discover.add_argument("--show-browser", action="store_true", help="headless=False，實際看瀏覽器")

    pcake_fetch = sub.add_parser("cake-fetch", help="用 discover 的結論抓職缺（需先完成 discover_source 分析）")
    pcake_fetch.add_argument("--keyword", default="devops")
    pcake_fetch.add_argument("--location", default=None)

    args = parser.parse_args()

    if args.cmd == "cake-discover":
        cakeresume_discover.discover_source(
            keyword=args.keyword, location=args.location, show_browser=args.show_browser
        )

    elif args.cmd == "cake-fetch":
        jobs = cakeresume_discover.fetch_jobs(keyword=args.keyword, location=args.location)
        print(json.dumps(jobs[:2], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
