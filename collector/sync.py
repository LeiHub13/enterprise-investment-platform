"""采集服务 CLI：按企业名单拉取招聘信号并经 Spring Boot 入库。

用法：
  py -3 -m collector.sync --companies companies.sample.csv --dry-run
  py -3 -m collector.sync --companies companies.sample.csv --watch --interval-hours 24
  py -3 -m collector.sync --status
"""
import argparse
import csv
import os
import sys
import time

from . import config, normalize, qcc, uploader


def load_companies(path: str) -> list[dict]:
    rows = []
    with open(path, encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            name = (row.get("name") or row.get("企业名称") or "").strip()
            uscc = (row.get("uscc") or row.get("统一社会信用代码") or "").strip()
            tier = (row.get("tier") or "B").strip().upper() or "B"
            if name or uscc:
                rows.append({"name": name, "uscc": uscc, "tier": tier})
    return rows


def run_once(companies: list[dict], dry_run: bool, max_pages: int = 1) -> dict:
    stats = {"created": 0, "updated": 0, "duplicate": 0, "stale": 0, "skipped": 0, "failed": 0}
    for company in companies:
        label = company["uscc"] or company["name"]
        try:
            resolved = uploader.resolve_company(company["name"], company["uscc"])
            if not resolved:
                stats["skipped"] += 1
                print(f"- 跳过（企业资源库未找到）：{label}")
                continue
            result = qcc.fetch_recruitment_pages(company["name"], company["uscc"], dry_run=dry_run, max_pages=max_pages)
            items = result["items"]
            if result["possiblyTruncated"]:
                print(f"! {label} 的结果可能有更多页，当前仅查询 {result['pagesFetched']} 页。")
            snapshot = normalize.normalize(resolved["name"], resolved.get("uscc") or company["uscc"],
                                           items, dry_run=dry_run)
            if snapshot is None:
                stats["skipped"] += 1
                print(f"- 无招聘记录：{label}")
                continue
            result = uploader.upload_recruitment(resolved["id"], snapshot, dry_run=dry_run)
            if result.get("created") and result.get("changeType") == "UPDATED":
                stats["updated"] += 1
                print(f"~ 已更新 #{result.get('recordId')}：{snapshot['title']}")
            elif result.get("created"):
                stats["created"] += 1
                print(f"+ 新记录 #{result.get('recordId')}：{snapshot['title']}")
            elif result.get("changeType") == "STALE":
                stats["stale"] += 1
                print(f"= 忽略较早的来源版本：{snapshot['title']}")
            else:
                stats["duplicate"] += 1
                print(f"= 已存在（去重）：{snapshot['title']}")
        except (qcc.QccError, uploader.UploadError) as error:
            stats["failed"] += 1
            print(f"! 失败：{label}：{error}")
    return stats


def print_status():
    payload = uploader.status()
    sources = payload.get("sources") or []
    if not sources:
        print("暂无采集记录。")
        return
    print("来源      类型          总数  演示  近24h  最近采集")
    for row in sources:
        print(f"{row.get('source', ''):<8}  {row.get('recordType', ''):<12}  {row.get('recordCount', 0):>4}  {row.get('demoCount', 0):>4}  "
              f"{row.get('fetched24h', 0):>5}  {str(row.get('lastFetchedAt') or '—')[:19]}")


def expire_due(max_batches: int = 10) -> int:
    """Bound each scan; repeated runs are safe and emit one expiry event per record."""
    total = 0
    for _ in range(max_batches):
        count = uploader.expire_records(100)
        total += count
        if count < 100:
            break
    return total


def main():
    parser = argparse.ArgumentParser(description="招商数据采集服务（招聘信号）")
    parser.add_argument("--companies", default="companies.sample.csv", help="企业名单 CSV（name/uscc/tier）")
    parser.add_argument("--dry-run", action="store_true", help="使用本地样例数据，不调用企查查、不消耗额度")
    parser.add_argument("--watch", action="store_true", help="按间隔循环采集")
    parser.add_argument("--interval-hours", type=float, default=config.DEFAULT_INTERVAL_HOURS)
    parser.add_argument("--max-pages", type=int, default=1, choices=range(1, 11), help="每家企业最多查询页数；每页可能计费")
    parser.add_argument("--publish-events", action="store_true", help="本轮后投递待发送事件到 RabbitMQ")
    parser.add_argument("--expire-records", action="store_true", help="本轮后扫描并标记到期来源记录")
    parser.add_argument("--expire-only", action="store_true", help="仅扫描到期记录，不查询第三方")
    parser.add_argument("--status", action="store_true", help="查看采集入库状态")
    args = parser.parse_args()
    if not config.COLLECTOR_TOKEN:
        print("未配置 COLLECTOR_TOKEN：请在 collector/.env 填写与后端一致的采集令牌。")
        return 2
    try:
        if args.status:
            print_status()
            return 0
        if args.expire_only:
            print(f"本轮标记失效记录：{expire_due()}")
            return 0
        companies = load_companies(args.companies)
        if not companies:
            print("企业名单为空。")
            return 2
        print(f"名单 {len(companies)} 家，模式：{'dry-run 样例' if args.dry_run else '企查查招聘接口'}")
        while True:
            stats = run_once(companies, args.dry_run, args.max_pages)
            if args.expire_records:
                try:
                    print(f"本轮标记失效记录：{expire_due()}")
                except uploader.UploadError as error:
                    stats["failed"] += 1
                    print(f"! 失效扫描失败：{error}")
            if args.publish_events:
                from .events.publisher import PublishError, publish_pending
                try:
                    print(f"RabbitMQ 已确认事件：{publish_pending(os.environ.get('RABBITMQ_URL', ''))}")
                except PublishError as error:
                    stats["failed"] += 1
                    print(f"! 事件投递失败：{error}")
            print(f"本轮：新增 {stats['created']}，更新 {stats['updated']}，重复 {stats['duplicate']}，"
                  f"较早版本 {stats['stale']}，跳过 {stats['skipped']}，失败 {stats['failed']}")
            if not args.watch:
                return 0 if stats["failed"] == 0 else 1
            time.sleep(max(60.0, args.interval_hours * 3600))
    except KeyboardInterrupt:
        print("已停止。")
        return 0
    except uploader.UploadError as error:
        print(f"! {error}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
