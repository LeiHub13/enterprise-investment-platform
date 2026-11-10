"""企查查招聘 API 连接器：复用标讯雷达的企查查签名方式（key + timestamp + MD5 签名）。

注意：企查查招聘接口（开放平台 718）需企业实名与应用场景审核后开通；
开通前使用 --dry-run 的本地样例验证链路。查询参数和签名以公开的 718 文档为准。
"""
import hashlib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from . import config

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "qcc_recruitment_sample.json"


class QccError(ValueError):
    pass


def fetch_recruitment_pages(company_name: str, uscc: str = "", *, dry_run: bool = False,
                            page_size: int = 50, max_pages: int = 1) -> dict:
    keyword = uscc.strip().upper() or company_name.strip()
    if not keyword or len(keyword) > 255:
        raise QccError("必须提供有效的企业名称或统一社会信用代码。")
    if uscc.strip() and not re.fullmatch(r"[0-9A-Z]{18}", uscc.strip().upper()):
        raise QccError("统一社会信用代码应为 18 位字母或数字。")
    if isinstance(page_size, bool) or not isinstance(page_size, int) or not 1 <= page_size <= 50:
        raise QccError("企查查招聘每页数量应为 1～50。")
    if isinstance(max_pages, bool) or not isinstance(max_pages, int) or not 1 <= max_pages <= 10:
        raise QccError("最多查询页数应为 1～10。")
    if dry_run:
        items = json.loads(FIXTURE.read_text(encoding="utf-8"))
        if not isinstance(items, list):
            raise QccError("招聘演示数据格式无效。")
        return {"items": items, "pagesFetched": 0, "possiblyTruncated": False, "demo": True}
    if not config.QCC_APP_KEY or not config.QCC_SECRET_KEY:
        raise QccError("未配置 QCC_APP_KEY / QCC_SECRET_KEY，企查查招聘接口不可用；可先用 --dry-run 验证链路。")
    if config.QCC_SEARCH_PARAM != "searchKey":
        raise QccError("企查查 718 的企业查询参数必须使用官方文档中的 searchKey。")
    items = []
    for page in range(1, max_pages + 1):
        fetched = _fetch_page(keyword, page, page_size)
        items.extend(fetched)
        if len(fetched) < page_size:
            return {"items": items, "pagesFetched": page, "possiblyTruncated": False, "demo": False}
    return {"items": items, "pagesFetched": max_pages, "possiblyTruncated": True, "demo": False}


def fetch_recruitment(company_name: str, uscc: str = "", *, dry_run: bool = False) -> list[dict]:
    return fetch_recruitment_pages(company_name, uscc, dry_run=dry_run)["items"]


def _fetch_page(keyword: str, page: int, page_size: int) -> list[dict]:
    timestamp = str(int(time.time()))
    signature = hashlib.md5(
        (config.QCC_APP_KEY + timestamp + config.QCC_SECRET_KEY).encode("utf-8")).hexdigest().upper()
    params = {"key": config.QCC_APP_KEY, "searchKey": keyword, "pageIndex": page, "pageSize": page_size}
    request = urllib.request.Request(
        config.QCC_RECRUITMENT_URL + "?" + urllib.parse.urlencode(params),
        headers={"Token": signature, "Timespan": timestamp, "Accept": "application/json"})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read(4_000_001)
            if len(raw) > 4_000_000:
                raise QccError("企查查招聘接口响应过大。")
            payload = json.loads(raw)
            break
        except urllib.error.HTTPError as error:
            if attempt == 0 and (error.code == 429 or 500 <= error.code <= 599):
                time.sleep(1)
                continue
            raise QccError(f"企查查招聘接口返回 HTTP {error.code}。") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt == 0:
                time.sleep(1)
                continue
            # 不复述异常与 URL：其中含 AppKey
            raise QccError("无法连接企查查招聘接口，请检查网络、密钥与接口开通状态。") from None
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise QccError("企查查招聘接口返回的 JSON 无法解析。") from None
    if not isinstance(payload, dict):
        raise QccError("企查查返回格式无法识别。")
    status = str(payload.get("Status") or payload.get("status") or "")
    if status and status not in ("200", "0"):
        raise QccError("企查查返回失败，请检查接口开通状态与查询参数。")
    result = payload.get("Result", payload.get("result"))
    for candidate in (result, (result or {}).get("Items") if isinstance(result, dict) else None,
                      (result or {}).get("List") if isinstance(result, dict) else None,
                      payload.get("Data")):
        if isinstance(candidate, list):
            return candidate
    raise QccError("企查查招聘接口返回结构无法识别，请按已授权的正式文档核对。")
