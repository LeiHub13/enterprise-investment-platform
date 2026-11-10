"""企查查 API 2001 企业信息核验；仅保留获准使用的主体字段。"""
import hashlib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from . import config
from .qcc import QccError

VERIFY_URL = "https://api.qichacha.com/EnterpriseInfo/Verify"


def verify_company(company_name: str, uscc: str = "", *, dry_run: bool = False) -> dict:
    name, credit = (company_name or "").strip(), (uscc or "").strip().upper()
    if not name or len(name) > 255:
        raise QccError("必须提供有效的企业全称。")
    if credit and not re.fullmatch(r"[0-9A-Z]{18}", credit):
        raise QccError("统一社会信用代码应为 18 位字母或数字。")
    if dry_run:
        return {"orderRef": "DEMO-" + uuid.uuid4().hex, "demo": True, "found": True,
                "verifiedName": "【演示】企业核验样例", "verifiedUscc": "913200000000000001",
                "businessStatus": "示例状态", "registeredAddress": None}
    if not config.QCC_APP_KEY or not config.QCC_SECRET_KEY:
        raise QccError("未配置 QCC_APP_KEY / QCC_SECRET_KEY，企查查核验接口不可用。")
    timestamp = str(int(time.time()))
    signature = hashlib.md5((config.QCC_APP_KEY + timestamp + config.QCC_SECRET_KEY).encode("utf-8")).hexdigest().upper()
    query = urllib.parse.urlencode({"key": config.QCC_APP_KEY, "searchKey": credit or name})
    request = urllib.request.Request(VERIFY_URL + "?" + query,
        headers={"Token": signature, "Timespan": timestamp, "Accept": "application/json"})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read(4_000_001)
            if len(raw) > 4_000_000:
                raise QccError("企查查核验接口响应过大。")
            payload = json.loads(raw)
            break
        except urllib.error.HTTPError as error:
            if attempt == 0 and (error.code == 429 or 500 <= error.code <= 599):
                time.sleep(1)
                continue
            raise QccError(f"企查查核验接口返回 HTTP {error.code}。") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt == 0:
                time.sleep(1)
                continue
            raise QccError("无法连接企查查核验接口，请检查网络、密钥和接口授权。") from None
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise QccError("企查查核验接口返回的 JSON 无法解析。") from None
    if not isinstance(payload, dict) or str(payload.get("Status") or "") != "200":
        raise QccError("企查查核验未成功，请检查接口开通状态与查询参数。")
    result = payload.get("Result")
    order = str(payload.get("OrderNumber") or "").strip()
    if not isinstance(result, dict) or not order or len(order) > 128:
        raise QccError("企查查核验返回结构或来源流水号无法识别。")
    found = result.get("VerifyResult")
    if isinstance(found, bool) or found not in (0, 1):
        raise QccError("企查查核验结果无法识别。")
    data = result.get("Data") if found == 1 else {}
    if not isinstance(data, dict):
        raise QccError("企查查核验主体信息无法识别。")
    def field(key, limit):
        value = data.get(key)
        return str(value).strip()[:limit] if value is not None else None
    return {"orderRef": order, "demo": False, "found": bool(found), "verifiedName": field("Name", 1000),
            "verifiedUscc": field("CreditCode", 50), "businessStatus": field("Status", 100),
            "registeredAddress": field("Address", 1000)}
