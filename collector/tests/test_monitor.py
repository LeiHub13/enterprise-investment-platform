import os
import subprocess
import sys

from collector import monitor, qcc, uploader


TASK = {"id": 9, "enterpriseId": 2, "enterpriseName": "【演示】测试企业", "uscc": "",
        "mode": "DEMO", "maxPages": 1, "leaseToken": "lease-test"}


def test_demo_task_fetches_fixture_and_records_success_without_a_real_request(monkeypatch):
    completed, uploads = [], []
    monkeypatch.setattr(uploader, "claim_monitor", lambda limit: [TASK])
    monkeypatch.setattr(qcc, "fetch_recruitment_pages", lambda name, uscc, **kwargs:
        {"items": [{"JobName": "演示岗位", "PublishDate": "2026-11-09"}],
         "pagesFetched": 0, "possiblyTruncated": False, "demo": True} if kwargs["dry_run"] else None)
    monkeypatch.setattr(uploader, "upload_recruitment", lambda company, snapshot, dry_run:
        uploads.append((company, snapshot, dry_run)) or {"created": True, "changeType": "NEW"})
    monkeypatch.setattr(uploader, "finish_monitor", lambda task, payload: completed.append((task, payload)))
    result = monitor.run_once()
    assert result["status"] == "SUCCESS" and result["createdRecords"] == 1
    assert uploads[0][0] == 2 and uploads[0][2] is True and "示例" in uploads[0][1]["title"]
    assert completed[0][1]["leaseToken"] == "lease-test" and completed[0][1]["errorCode"] is None


def test_missing_qcc_key_records_fixed_error_without_sensitive_details(monkeypatch):
    completed = []
    monkeypatch.setattr(uploader, "claim_monitor", lambda limit: [{**TASK, "mode": "LIVE"}])
    monkeypatch.setattr(monitor.config, "QCC_APP_KEY", "")
    monkeypatch.setattr(monitor.config, "QCC_SECRET_KEY", "")
    monkeypatch.setattr(qcc, "fetch_recruitment_pages", lambda *args, **kwargs: (_ for _ in ()).throw(qcc.QccError("private details")))
    monkeypatch.setattr(uploader, "finish_monitor", lambda task, payload: completed.append(payload))
    result = monitor.run_once()
    assert result["status"] == "FAILED" and result["errorCode"] == "QCC_CONFIG"
    assert "private details" not in str(completed)


def test_idle_monitor_makes_no_external_request(monkeypatch):
    monkeypatch.setattr(uploader, "claim_monitor", lambda limit: [])
    monkeypatch.setattr(qcc, "fetch_recruitment_pages", lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError()))
    assert monitor.run_once() == {"status": "IDLE"}


def test_broker_components_are_url_encoded_in_a_fresh_process():
    env = {**os.environ, "COLLECTOR_SKIP_ENV_FILE": "1", "RABBITMQ_URL": "", "RABBITMQ_HOST": "rabbitmq",
           "RABBITMQ_USER": "user@name", "RABBITMQ_PASSWORD": "p@ss:/word", "RABBITMQ_VHOST": "/", "RABBITMQ_PORT": "5672"}
    result = subprocess.run([sys.executable, "-c", "import os; import collector.config; print(os.environ['RABBITMQ_URL'])"],
                            env=env, capture_output=True, text=True, check=True)
    assert result.stdout.strip() == "amqp://user%40name:p%40ss%3A%2Fword@rabbitmq:5672/%2F"
