"""Optional HTTP/MySQL/RabbitMQ integration; only for an explicitly isolated local stack."""
import json
import os
import time
import uuid
from datetime import date, datetime, timedelta, timezone
from urllib.parse import urlparse

import httpx
import pika
import pytest

from collector import config, uploader
from collector.events import publisher
from collector.connectors.fifty_eight import RentalRecord


@pytest.mark.skipif(os.environ.get("COLLECTOR_IT_ISOLATED") != "1", reason="isolated integration stack not explicitly enabled")
def test_demo_record_flows_from_api_outbox_to_rabbitmq(monkeypatch):
    base = os.environ["TEST_APP_URL"].rstrip("/")
    broker = os.environ["TEST_RABBITMQ_URL"]
    if urlparse(base).hostname not in {"127.0.0.1", "localhost"}:
        pytest.fail("integration test must target a local isolated stack")
    monkeypatch.setattr(config, "BUSINESS_BASE_URL", base)
    monkeypatch.setattr(config, "COLLECTOR_TOKEN", os.environ["TEST_COLLECTOR_TOKEN"])
    queue = "investment.collector.integration." + uuid.uuid4().hex
    company_id = None
    with httpx.Client(base_url=base, timeout=15, trust_env=False) as client:
        login = client.post("/api/auth/login", json={"username": os.environ["TEST_APP_USER"],
                                                     "password": os.environ["TEST_APP_PASSWORD"]})
        login.raise_for_status()
        headers = {"Authorization": "Bearer " + login.json()["token"]}
        try:
            name = "【自动化演示测试】" + uuid.uuid4().hex
            company = client.post("/api/enterprises", headers=headers, json={"name": name})
            company.raise_for_status()
            company_id = company.json()["id"]
            snapshot = {"externalId": "DEMO-" + uuid.uuid4().hex, "title": "【演示】招聘测试",
                        "summary": "演示数据", "posts": [{"title": "演示岗位", "publishedAt": date.today().isoformat()}],
                        "publishedAt": date.today().isoformat(), "observedAt": date.today().isoformat(),
                        "strength": 1, "confidence": 0.35}
            record = uploader.upload_recruitment(company_id, snapshot, dry_run=True)
            assert record["created"] is True
            updated_snapshot = {**snapshot, "summary": "演示数据已更新",
                                "posts": snapshot["posts"] + [{"title": "演示新增岗位", "publishedAt": date.today().isoformat()}]}
            updated = uploader.upload_recruitment(company_id, updated_snapshot, dry_run=True)
            assert updated["created"] is True and updated["changeType"] == "UPDATED" and updated["version"] == 2
            assert uploader.upload_recruitment(company_id, updated_snapshot, dry_run=True)["changeType"] == "UNCHANGED"
            now = datetime.now(timezone.utc)
            rental = RentalRecord("DEMO-" + uuid.uuid4().hex, name, "南京", "鼓楼区", 100, 200, None,
                                  now, now, now + timedelta(seconds=8), None)
            rental_record = uploader.upload_rental_demo(company_id, rental)
            assert rental_record["created"] is True and rental_record["validityStatus"] == "VALID"
            signals_response = client.get(f"/api/enterprises/{company_id}/signals", headers=headers)
            signals_response.raise_for_status()
            signals = signals_response.json()
            assert any(signal["sourceRecordId"] == record["recordId"] and signal["validityStatus"] == "SUPERSEDED" for signal in signals)
            assert any(signal["sourceRecordId"] == rental_record["recordId"] and signal.get("expiresAt") for signal in signals)
            time.sleep(max(0, (rental.expires_at - datetime.now(timezone.utc)).total_seconds()) + 1.5)
            assert uploader.expire_records(100) >= 1
            assert publisher.publish_pending(broker, queue=queue) >= 4
            connection = pika.BlockingConnection(pika.URLParameters(broker))
            try:
                channel = connection.channel()
                received = []
                while True:
                    method, properties, body = channel.basic_get(queue=queue, auto_ack=False)
                    if method is None:
                        break
                    received.append(json.loads(body))
                    channel.basic_ack(method.delivery_tag)
                assert any(event["sourceRecordId"] == record["recordId"] and event["isDemo"] == 1 for event in received)
                assert any(event["sourceRecordId"] == updated["recordId"] and event["eventType"] == "COLLECTOR_RECORD_UPDATED"
                           and event["recordVersion"] == 2 for event in received)
                assert any(event["sourceRecordId"] == rental_record["recordId"] and event["isDemo"] == 1 for event in received)
                assert any(event["sourceRecordId"] == rental_record["recordId"] and event["eventType"] == "COLLECTOR_RECORD_EXPIRED"
                           and event["validityStatus"] == "EXPIRED" for event in received)
                channel.queue_delete(queue=queue)
            finally:
                connection.close()
        finally:
            if company_id is not None:
                deletion = client.delete(f"/api/enterprises/{company_id}", headers=headers)
                deletion.raise_for_status()
            client.post("/api/auth/logout", headers=headers)
