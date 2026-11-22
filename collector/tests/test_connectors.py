import hashlib
import json
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs, urlparse

import pytest

from collector import qcc, sync, uploader
from collector.connectors.fifty_eight import FiftyEightRentalConnector, MockRentalConnector, RentalQuery, RentalRecord


def test_qcc_paginates_using_documented_parameters(monkeypatch):
    monkeypatch.setattr(qcc.config, "QCC_APP_KEY", "test-app")
    monkeypatch.setattr(qcc.config, "QCC_SECRET_KEY", "test-secret")
    monkeypatch.setattr(qcc.config, "QCC_SEARCH_PARAM", "searchKey")
    monkeypatch.setattr(qcc.time, "time", lambda: 1700000000)
    requests = []

    class Response:
        def __init__(self, payload): self.payload = payload
        def __enter__(self): return self
        def __exit__(self, *args): return False
        def read(self, limit): return json.dumps(self.payload).encode()

    def open_request(request, timeout):
        requests.append(request)
        page = int(parse_qs(urlparse(request.full_url).query)["pageIndex"][0])
        return Response({"Status": "200", "Result": [{"Id": str(i)} for i in (range(2) if page == 1 else range(1))]})

    monkeypatch.setattr(qcc.urllib.request, "urlopen", open_request)
    result = qcc.fetch_recruitment_pages("测试企业", page_size=2, max_pages=3)
    assert len(result["items"]) == 3 and result["pagesFetched"] == 2 and not result["possiblyTruncated"]
    params = parse_qs(urlparse(requests[0].full_url).query)
    assert params["searchKey"] == ["测试企业"] and params["pageSize"] == ["2"]
    expected = hashlib.md5(b"test-app1700000000test-secret").hexdigest().upper()
    assert requests[0].get_header("Token") == expected


@pytest.mark.parametrize("kwargs", [
    {"company_name": ""}, {"company_name": "企业", "uscc": "not-valid"},
    {"company_name": "企业", "page_size": 51}, {"company_name": "企业", "max_pages": 0},
])
def test_qcc_validates_parameters_even_in_demo_mode(kwargs):
    with pytest.raises(qcc.QccError):
        qcc.fetch_recruitment_pages(**kwargs, dry_run=True)


def test_unrecognized_qcc_response_is_not_treated_as_no_jobs(monkeypatch):
    monkeypatch.setattr(qcc.config, "QCC_APP_KEY", "test-app")
    monkeypatch.setattr(qcc.config, "QCC_SECRET_KEY", "test-secret")
    monkeypatch.setattr(qcc.config, "QCC_SEARCH_PARAM", "searchKey")
    class Response:
        def __enter__(self): return self
        def __exit__(self, *args): return False
        def read(self, limit): return b'{"Status":"200","unexpected":{}}'
    monkeypatch.setattr(qcc.urllib.request, "urlopen", lambda *args, **kwargs: Response())
    with pytest.raises(qcc.QccError, match="结构无法识别"):
        qcc.fetch_recruitment_pages("测试企业")


def test_mock_rentals_are_marked_paginated_and_expired_items_are_excluded():
    now = datetime(2026, 11, 9, tzinfo=timezone.utc)
    def record(key, expiry):
        return RentalRecord(key, None, "南京", "鼓楼", 100, 200, None,
                            now - timedelta(days=1), now - timedelta(hours=1), expiry, None)
    connector = MockRentalConnector([record("a", now + timedelta(days=1)),
                                     record("b", now + timedelta(days=1)),
                                     record("expired", now - timedelta(seconds=1))], now=now)
    first = connector.fetch(RentalQuery(city="南京", limit=1))
    second = connector.fetch(RentalQuery(city="南京", limit=1, cursor=first.next_cursor))
    assert first.demo and second.demo
    assert [item.external_id for item in first.records + second.records] == ["a", "b"]
    assert second.next_cursor is None
    with pytest.raises(NotImplementedError):
        FiftyEightRentalConnector().fetch(RentalQuery(city="南京"))


def test_demo_rental_upload_is_explicitly_marked(monkeypatch):
    now = datetime.now(timezone.utc)
    record = RentalRecord("DEMO:123", "演示企业", "南京", "鼓楼区", 100, 200, None,
                          now, now, now + timedelta(days=7), None)
    captured = []
    monkeypatch.setattr(uploader, "_request", lambda path, payload: captured.append((path, payload)) or {"created": True})
    uploader.upload_rental_demo(3, record)
    assert captured[0][1]["rawPayload"]["demo"] is True
    assert captured[0][1]["expiresAt"] == record.expires_at.isoformat()
    assert captured[0][1]["sourceUpdatedAt"] == record.updated_at.isoformat()
    assert "【演示】" in captured[0][1]["title"]
    assert captured[0][1]["source"] == "58"


def test_expiry_scan_is_bounded_and_stops_after_a_short_batch(monkeypatch):
    batches = iter([100, 100, 3])
    monkeypatch.setattr(uploader, "expire_records", lambda limit: next(batches))
    assert sync.expire_due() == 203
