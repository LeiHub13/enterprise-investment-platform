"""入库上传：解析企业 → 写原始记录与信号（经 Spring Boot 采集接口，带服务令牌）。"""
import json
import urllib.error
import urllib.parse
import urllib.request

from . import config


class UploadError(ValueError):
    pass


def _request(path: str, *, payload=None, timeout=20):
    url = config.BUSINESS_BASE_URL + path
    headers = {"X-Collector-Token": config.COLLECTOR_TOKEN, "Accept": "application/json"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers,
                                     method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        try:
            message = json.loads(error.read().decode("utf-8")).get("message")
        except (json.JSONDecodeError, UnicodeDecodeError):
            message = None
        raise UploadError(message or f"业务接口返回 {error.code}") from None
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError):
        raise UploadError("无法连接业务服务，请确认后端已启动并配置了 COLLECTOR_TOKEN。") from None


def resolve_company(name: str, uscc: str = "") -> dict | None:
    query = urllib.parse.urlencode({"uscc": uscc or "", "name": name})
    try:
        return _request("/api/collector/companies/resolve?" + query)
    except UploadError as error:
        if "未找到" in str(error):
            return None
        raise


def upload_recruitment(company_id: int, snapshot: dict, *, dry_run: bool = False) -> dict:
    return _request("/api/collector/records", payload={
        "source": "QCC",
        "recordType": "RECRUITMENT",
        "externalId": snapshot["externalId"],
        "enterpriseId": company_id,
        "title": snapshot["title"],
        "summary": snapshot["summary"],
        "rawPayload": {"demo": dry_run, "posts": snapshot["posts"]},
        "publishedAt": snapshot["publishedAt"],
        "observedAt": snapshot["observedAt"],
        "strength": snapshot["strength"],
        "confidence": snapshot["confidence"],
    })


def upload_rental_demo(company_id: int, record) -> dict:
    if record.demo is not True:
        raise UploadError("演示上传只接受明确标记为 demo 的求租记录。")
    return _request("/api/collector/records", payload={
        "source": "58",
        "recordType": "RENTAL",
        "externalId": record.external_id,
        "enterpriseId": company_id,
        "title": "【演示】58 模拟办公求租（未接入正式接口）",
        "summary": f"演示区域：{record.city}{record.district or ''}；目标面积：{record.area_min or '未知'}～{record.area_max or '未知'}㎡。",
        "rawPayload": {"demo": True, "areaMin": record.area_min, "areaMax": record.area_max,
                       "expiresAt": record.expires_at.isoformat() if record.expires_at else None},
        "publishedAt": record.published_at.date().isoformat(),
        "observedAt": record.published_at.date().isoformat(),
        "expiresAt": record.expires_at.isoformat() if record.expires_at else None,
        "sourceUpdatedAt": record.updated_at.isoformat(),
        "strength": 1,
        "confidence": 0.35,
    })


def status() -> dict:
    return _request("/api/collector/status")


def expire_records(limit: int = 100) -> int:
    if not 1 <= limit <= 100:
        raise UploadError("失效扫描批量大小必须为 1～100。")
    return int(_request("/api/collector/records/expire", payload={"limit": limit})["expired"])


def claim_monitor(limit: int = 1) -> list[dict]:
    if not 1 <= limit <= 10:
        raise UploadError("监测任务领取数量必须为 1～10。")
    return _request("/api/collector/monitor/claim", payload={"limit": limit})


def finish_monitor(task_id: int, payload: dict) -> dict:
    return _request(f"/api/collector/monitor/{task_id}/finish", payload=payload)


def latest_verification(company_id: int) -> dict:
    return _request(f"/api/collector/verifications/companies/{company_id}/latest")


def upload_verification(company_id: int, result: dict) -> dict:
    fields = ("orderRef", "demo", "found", "verifiedName", "verifiedUscc", "businessStatus", "registeredAddress")
    return _request("/api/collector/verifications", payload={"enterpriseId": company_id, **{key: result.get(key) for key in fields}})


def claim_outbox(limit: int = 20) -> list[dict]:
    return _request("/api/collector/outbox/claim", payload={"limit": limit})


def ack_outbox(event_id: int, lease_token: str) -> dict:
    return _request(f"/api/collector/outbox/{event_id}/ack", payload={"leaseToken": lease_token})


def retry_outbox(event_id: int, lease_token: str) -> dict:
    return _request(f"/api/collector/outbox/{event_id}/retry", payload={"leaseToken": lease_token})
