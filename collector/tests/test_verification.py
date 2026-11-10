import hashlib
import json
from urllib.parse import parse_qs, urlparse

import pytest

from collector import config, monitor, qcc, qcc_verification, uploader


class Response:
    def __init__(self, payload):
        self.payload = json.dumps(payload, ensure_ascii=False).encode()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self, size):
        return self.payload[:size]


def test_qcc_request_signature_and_allowlisted_fields(monkeypatch):
    monkeypatch.setattr(config, "QCC_APP_KEY", "app-test")
    monkeypatch.setattr(config, "QCC_SECRET_KEY", "secret-test")
    monkeypatch.setattr(qcc_verification.time, "time", lambda: 1700000000)
    captured = []
    def fetch(request, timeout):
        captured.append(request)
        return Response({"Status": "200", "OrderNumber": "test-order", "Result": {"VerifyResult": 1,
            "Data": {"Name": "测试企业", "CreditCode": "913200000000000001", "Status": "存续", "Address": "测试地址",
                     "OperName": "PRIVATE-SENTINEL", "ContactInfo": "PRIVATE-SENTINEL", "BankInfo": "PRIVATE-SENTINEL"}}})
    monkeypatch.setattr(qcc_verification.urllib.request, "urlopen", fetch)
    result = qcc_verification.verify_company("测试企业", "913200000000000001")
    assert result == {"orderRef": "test-order", "demo": False, "found": True, "verifiedName": "测试企业",
                      "verifiedUscc": "913200000000000001", "businessStatus": "存续", "registeredAddress": "测试地址"}
    request = captured[0]
    assert parse_qs(urlparse(request.full_url).query) == {"key": ["app-test"], "searchKey": ["913200000000000001"]}
    assert request.get_header("Timespan") == "1700000000"
    assert request.get_header("Token") == hashlib.md5(b"app-test1700000000secret-test").hexdigest().upper()
    assert "PRIVATE-SENTINEL" not in str(result)


def test_demo_is_offline_and_not_a_real_verification(monkeypatch):
    monkeypatch.setattr(qcc_verification.urllib.request, "urlopen", lambda *args, **kwargs: pytest.fail("network called"))
    result = qcc_verification.verify_company("测试企业", dry_run=True)
    assert result["demo"] is True and result["orderRef"].startswith("DEMO-")
    with pytest.raises(qcc.QccError):
        qcc_verification.verify_company("测试企业", "invalid", dry_run=True)


@pytest.mark.parametrize("payload", [
    {"Status": "403", "Result": {}},
    {"Status": "200", "OrderNumber": "order", "Result": {"VerifyResult": 2}},
    {"Status": "200", "Result": {"VerifyResult": 1, "Data": {}}},
])
def test_unrecognized_provider_responses_fail_closed(monkeypatch, payload):
    monkeypatch.setattr(config, "QCC_APP_KEY", "app-test")
    monkeypatch.setattr(config, "QCC_SECRET_KEY", "secret-test")
    monkeypatch.setattr(qcc_verification.urllib.request, "urlopen", lambda *args, **kwargs: Response(payload))
    with pytest.raises(qcc.QccError):
        qcc_verification.verify_company("测试企业")


def test_not_found_is_explicit(monkeypatch):
    monkeypatch.setattr(config, "QCC_APP_KEY", "app-test")
    monkeypatch.setattr(config, "QCC_SECRET_KEY", "secret-test")
    monkeypatch.setattr(qcc_verification.urllib.request, "urlopen", lambda *args, **kwargs:
                        Response({"Status": "200", "OrderNumber": "order", "Result": {"VerifyResult": 0}}))
    result = qcc_verification.verify_company("测试企业")
    assert result["found"] is False and result["verifiedName"] is None


@pytest.mark.parametrize("mode,fresh,enabled,expected", [
    ("DEMO", False, True, 0), ("LIVE", False, False, 0), ("LIVE", True, True, 0), ("LIVE", False, True, 1),
])
def test_monitor_only_verifies_stale_live_company_with_explicit_opt_in(monkeypatch, mode, fresh, enabled, expected):
    task = {"id": 9, "enterpriseId": 2, "enterpriseName": "测试企业", "uscc": "", "mode": mode,
            "maxPages": 1, "leaseToken": "lease"}
    calls = []
    monkeypatch.setattr(config, "QCC_VERIFY_ON_MONITOR", enabled)
    monkeypatch.setattr(uploader, "claim_monitor", lambda limit: [task])
    monkeypatch.setattr(uploader, "latest_verification", lambda company: {"verificationFresh": fresh})
    monkeypatch.setattr(qcc_verification, "verify_company", lambda *args: calls.append(args) or {"demo": False})
    monkeypatch.setattr(uploader, "upload_verification", lambda *args: None)
    monkeypatch.setattr(qcc, "fetch_recruitment_pages", lambda *args, **kwargs:
                        {"items": [], "pagesFetched": 1, "possiblyTruncated": False})
    monkeypatch.setattr(uploader, "finish_monitor", lambda *args: None)
    assert monitor.run_once()["status"] == "EMPTY"
    assert len(calls) == expected


def test_verification_failure_does_not_leak_provider_error_or_stop_recruitment(monkeypatch):
    task = {"id": 9, "enterpriseId": 2, "enterpriseName": "测试企业", "mode": "LIVE", "leaseToken": "lease"}
    monkeypatch.setattr(config, "QCC_VERIFY_ON_MONITOR", True)
    monkeypatch.setattr(uploader, "claim_monitor", lambda limit: [task])
    monkeypatch.setattr(uploader, "latest_verification", lambda company: {"verificationFresh": False})
    monkeypatch.setattr(qcc_verification, "verify_company", lambda *args: (_ for _ in ()).throw(qcc.QccError("private")))
    monkeypatch.setattr(qcc, "fetch_recruitment_pages", lambda *args, **kwargs:
                        {"items": [], "pagesFetched": 1, "possiblyTruncated": False})
    monkeypatch.setattr(uploader, "finish_monitor", lambda *args: None)
    result = monitor.run_once()
    assert result["status"] == "EMPTY" and result["verificationWarning"] == "QCC_VERIFICATION_FAILED"
    assert "private" not in str(result)
