"""招聘快照标准化：只保留获准使用的字段，生成入库记录与信号。"""
from datetime import date, datetime


def _text(item: dict, *keys: str, limit: int = 200) -> str:
    for key in keys:
        value = item.get(key)
        if value not in (None, ""):
            return str(value).strip()[:limit]
    return ""


def _date(value: str) -> str:
    value = (value or "").strip()
    for pattern in ("%Y-%m-%d", "%Y/%m/%d", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(value[:19], pattern).strftime("%Y-%m-%d")
        except ValueError:
            continue
    return ""


def normalize(company_name: str, uscc: str, items: list[dict], *, dry_run: bool = False) -> dict | None:
    posts = []
    for item in items:
        if not isinstance(item, dict):
            continue
        posts.append({
            "externalId": _text(item, "Id", limit=100),
            "title": _text(item, "JobName", "Title", "Position", "jobName"),
            "publishedAt": _date(_text(item, "StartDate", "PublishDate", "CreateTime", "Date", limit=19)),
            "salary": _text(item, "Salary", "salary", limit=60),
            "education": _text(item, "Education", "education", limit=30),
            "experience": _text(item, "Experience", "experience", limit=30),
            "location": _text(item, "Address", "City", "Location", "address", limit=120),
        })
    posts = [post for post in posts if post["title"]]
    if not posts:
        return None
    dates = sorted(post["publishedAt"] for post in posts if post["publishedAt"])
    latest = dates[-1] if dates else ""
    count = len(posts)
    strength = 3 if count >= 10 else 2 if count >= 3 else 1
    label = "示例招聘链路" if dry_run else "企查查招聘"
    title = f"{'【示例】' if dry_run else ''}{label}：{company_name} 招聘快照（{count} 个职位）"
    summary = "；".join(f"{post['title']}（{post['publishedAt'] or '日期待核实'}）" for post in posts[:5])
    if count > 5:
        summary += f" 等 {count} 个职位"
    snapshot = date.today().isoformat()
    return {
        "externalId": f"{uscc or company_name}:{snapshot}",
        "title": title[:255],
        "summary": summary[:15000],
        "posts": posts,
        "publishedAt": latest or None,
        "observedAt": latest or None,
        "strength": strength,
        "confidence": 0.35 if dry_run else 0.8,
    }
