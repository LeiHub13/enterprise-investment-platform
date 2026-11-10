"""Exercise the end-to-end workflow against a disposable, isolated test stack.

The fake enterprise and MANUAL source records are test fixtures, never real
market claims. The caller must destroy the disposable stack after this test.
"""
import json
import os
from pathlib import Path
import sys
import subprocess
import uuid
from datetime import date

import httpx
import pika

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from collector.events.consumer import consume_one


def events(text):
    result = []
    for block in text.split("\n\n"):
        kind = next((line[6:].strip() for line in block.splitlines() if line.startswith("event:")), None)
        data = next((line[5:].strip() for line in block.splitlines() if line.startswith("data:")), None)
        if kind and data:
            result.append((kind, json.loads(data)))
    return result


def main():
    base = os.environ.get("AGENT_E2E_BASE_URL", "")
    if os.environ.get("AGENT_E2E_ISOLATED") != "1" or not base.startswith("http://127.0.0.1:"):
        raise RuntimeError("Only a dedicated, explicitly isolated loopback test stack may be used")
    company_name = "【自动化测试非真实】" + uuid.uuid4().hex
    with httpx.Client(base_url=base, timeout=180, trust_env=False) as client:
        def request(method, path, **kwargs):
            response = client.request(method, path, **kwargs)
            if response.is_error:
                raise RuntimeError(f"{method} {path}: {response.status_code} {response.text[:500]}")
            return response

        schema = request("GET", "/api/openapi").json()
        assert all(path in schema["paths"] for path in ("/api/companies", "/api/buildings", "/api/monitor/tasks", "/api/agent/chat"))
        assert client.get("/api/companies").status_code == 401
        allowed = client.options("/api/companies", headers={"Origin": "http://127.0.0.1:5173",
            "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization"})
        assert allowed.status_code == 200 and allowed.headers.get("access-control-allow-origin") == "http://127.0.0.1:5173"
        denied = client.options("/api/companies", headers={"Origin": "https://untrusted.example", "Access-Control-Request-Method": "GET"})
        assert denied.status_code == 403
        swagger = client.get("/api/swagger-ui.html", follow_redirects=True)
        assert swagger.status_code == 200 and "Swagger UI" in swagger.text, f"Swagger UI unavailable: {swagger.url}"
        login = request("POST", "/api/auth/login", headers={"Origin": base},
                        json={"username": "agent_e2e_admin", "password": "IsolatedAgentTest123!"}).json()
        client.headers["Authorization"] = "Bearer " + login["token"]
        projects = request("GET", "/api/projects", params={"page": 1, "size": 100}).json()["items"]
        project = next(item for item in projects if item["projectName"] == "泛悦城市广场")
        assert request("GET", f"/api/buildings/{project['id']}").json()["projectName"] == "泛悦城市广场"
        company = request("POST", "/api/enterprises", json={"name": company_name, "provinceCode": "320000",
            "cityCode": "320100", "districtCode": "320106", "industryName": "软件", "businessStatus": "存续"}).json()
        assert request("GET", f"/api/companies/{company['id']}").json()["name"] == company_name
        assert request("GET", f"/api/companies/{company['id']}").json()["verificationStatus"] == "PENDING"
        verification = subprocess.run(["docker", "run", "--rm", "--network", os.environ["AGENT_E2E_NETWORK"],
            "-e", "BUSINESS_BASE_URL=http://business-api:8080", "-e", "COLLECTOR_TOKEN=isolated-test-only",
            "-e", "COLLECTOR_SKIP_ENV_FILE=1", "enterprise-resource-collector:latest", "python", "-m", "collector.verify",
            "--company", company_name, "--upload-demo"], capture_output=True, timeout=90)
        assert verification.returncode == 0, verification.stdout.decode("utf-8", errors="replace") + verification.stderr.decode("utf-8", errors="replace")
        assert request("GET", f"/api/companies/{company['id']}").json()["verificationStatus"] == "PENDING"
        history = request("GET", f"/api/companies/{company['id']}/verifications").json()
        assert len(history) == 1 and history[0]["resultStatus"] == "PENDING" and history[0]["isDemo"]
        task = request("POST", "/api/monitor/tasks", json={"enterpriseId": company["id"], "mode": "DEMO",
            "intervalMinutes": 1440, "maxPages": 1}).json()
        assert not task["enabled"]
        assert not request("POST", "/api/collector/monitor/claim", headers={"X-Collector-Token": "isolated-test-only"},
                           json={"limit": 1}).json()
        request("PUT", f"/api/monitor/tasks/{task['id']}", json={"enabled": True, "intervalMinutes": 1440, "maxPages": 1})
        worker = subprocess.run(["docker", "run", "--rm", "--network", os.environ["AGENT_E2E_NETWORK"],
            "-e", "BUSINESS_BASE_URL=http://business-api:8080", "-e", "COLLECTOR_TOKEN=isolated-test-only",
            "-e", "COLLECTOR_SKIP_ENV_FILE=1", "enterprise-resource-collector:latest", "python", "-m", "collector.monitor"],
            capture_output=True, timeout=90)
        assert worker.returncode == 0, worker.stderr.decode("utf-8", errors="replace")
        monitor_runs = request("GET", f"/api/monitor/tasks/{task['id']}/runs").json()
        assert len(monitor_runs) == 1 and monitor_runs[0]["status"] == "SUCCESS"
        records = {}
        for kind in ("RENTAL", "RECRUITMENT"):
            records[kind] = request("POST", "/api/collector/records", headers={"X-Collector-Token": "isolated-test-only"}, json={
                "source": "MANUAL", "recordType": kind, "externalId": uuid.uuid4().hex, "enterpriseId": company["id"],
                "title": "【自动化测试非真实】" + kind, "summary": "仅用于隔离环境测试，不代表市场信息",
                "rawPayload": {"testFixture": True}, "publishedAt": date.today().isoformat(),
                "observedAt": date.today().isoformat(), "strength": 1, "confidence": 0.5}).json()["recordId"]
        params = {"enterpriseId": company["id"], "projectId": project["id"]}
        assert request("GET", "/api/leads", params=params).json()["total"] == 0
        chat = request("POST", "/api/agent/chat", json={"conversationId": str(uuid.uuid4()),
            "projectId": project["id"], "message": f"请分析{company_name}是否适合在南京鼓楼区租用100～300平方米办公场地。",
            "source": "EXTERNAL"})
        first = events(chat.text)
        errors = [data for kind, data in first if kind == "error"]
        assert not errors, f"Agent error: {errors}"
        meta = next(data for kind, data in first if kind == "meta")
        assert any(kind == "review_required" for kind, _ in first), first
        assert request("GET", "/api/leads", params=params).json()["total"] == 0, "lead created before confirmation"
        tool_names = {data.get("tool") for kind, data in first if kind == "tool"}
        assert {"query_recruitment", "search_office_rentals"} <= tool_names, tool_names
        decision = request("POST", f"/api/agent/tasks/{meta['runId']}/decision", json={"approved": True})
        resumed = events(decision.text)
        errors = [data for kind, data in resumed if kind == "error"]
        assert not errors, f"Resume error: {errors}"
        lead_id = next(data["leadId"] for kind, data in resumed if kind == "lead_created")
        lead = request("GET", f"/api/leads/{lead_id}").json()
        assert lead["leadStatus"] == "PENDING_REVIEW"
        evidence = request("GET", f"/api/leads/{lead_id}/evidence").json()
        assert len(evidence) == 2 and all("自动化测试" in item["title"] for item in evidence)
        analysis = request("GET", f"/api/leads/{lead_id}/analysis").json()
        assert analysis["available"] and all(analysis.get(key) is not None for key in ("fitScore", "intentScore", "confidenceScore"))
        trace = request("GET", f"/api/agent/tasks/{meta['runId']}/trace").json()
        assert trace["scores"] and len(trace["tools"]) >= 2
        assert request("POST", f"/api/leads/{lead_id}/approve").json()["leadStatus"] == "NEW"
        request("POST", f"/api/leads/{lead_id}/followups", json={"followupType": "TEST", "content": "【自动化测试非真实】跟进记录"})
        assert request("GET", f"/api/leads/{lead_id}/followups").json()["total"] == 1
        claimed = request("POST", "/api/collector/outbox/claim", headers={"X-Collector-Token": "isolated-test-only"},
                          json={"limit": 100}).json()
        event = next(item for item in claimed if item["sourceRecordId"] == records["RECRUITMENT"])
        broker = os.environ["AGENT_E2E_RABBITMQ_URL"]
        queue = "investment.e2e." + uuid.uuid4().hex
        connection = pika.BlockingConnection(pika.URLParameters(broker))
        try:
            channel = connection.channel()
            channel.queue_declare(queue=queue, durable=True)
            demo_event = next(item for item in claimed if item.get("isDemo") == 1)
            demo_body = json.dumps({"id": demo_event["id"], "sourceRecordId": demo_event["sourceRecordId"]}).encode()
            channel.basic_publish(exchange="", routing_key=queue, body=demo_body,
                                  properties=pika.BasicProperties(message_id=str(demo_event["id"]), delivery_mode=2))
            assert consume_one(broker, base, login["token"], queue=queue)["status"] == "SKIPPED"
            body = json.dumps({"id": event["id"], "sourceRecordId": event["sourceRecordId"]}).encode()
            properties = pika.BasicProperties(message_id=str(event["id"]), delivery_mode=2)
            channel.basic_publish(exchange="", routing_key=queue, body=body, properties=properties)
            first = consume_one(broker, base, login["token"], queue=queue)
            assert first["action"] == "ACK" and first["status"] == "DONE", first
            channel.basic_publish(exchange="", routing_key=queue, body=body, properties=properties)
            repeated = consume_one(broker, base, login["token"], queue=queue)
            assert repeated["action"] == "ACK" and repeated["runId"] == first["runId"], repeated
            event_trace = request("GET", f"/api/agent/tasks/{first['runId']}/trace").json()
            assert event_trace["run"]["status"] == "DONE"
            assert request("GET", "/api/leads", params=params).json()["total"] == 1

            event_name = "【自动化测试非真实】事件企业" + uuid.uuid4().hex
            event_company = request("POST", "/api/enterprises", json={"name": event_name, "provinceCode": "320000",
                "cityCode": "320100", "districtCode": "320106", "industryName": "软件", "businessStatus": "存续"}).json()
            event_record = request("POST", "/api/collector/records", headers={"X-Collector-Token": "isolated-test-only"}, json={
                "source": "MANUAL", "recordType": "RENTAL", "externalId": uuid.uuid4().hex,
                "enterpriseId": event_company["id"], "title": "【自动化测试非真实】事件求租", "summary": "仅供隔离测试",
                "rawPayload": {"testFixture": True}, "publishedAt": date.today().isoformat(),
                "observedAt": date.today().isoformat(), "strength": 1, "confidence": 0.5}).json()["recordId"]
            claimed = request("POST", "/api/collector/outbox/claim", headers={"X-Collector-Token": "isolated-test-only"},
                              json={"limit": 100}).json()
            pending_event = next(item for item in claimed if item["sourceRecordId"] == event_record)
            pending_body = json.dumps({"id": pending_event["id"], "sourceRecordId": event_record}).encode()
            pending_properties = pika.BasicProperties(message_id=str(pending_event["id"]), delivery_mode=2)
            channel.basic_publish(exchange="", routing_key=queue, body=pending_body, properties=pending_properties)
            pending = consume_one(broker, base, login["token"], queue=queue)
            assert pending["action"] == "ACK" and pending["status"] == "AWAITING_REVIEW", pending
            assert request("GET", f"/api/agent/tasks/{pending['runId']}").json()["status"] == "AWAITING_REVIEW"
            awaiting = request("GET", "/api/agent/runs", params={"status": "AWAITING_REVIEW", "page": 1}).json()
            assert any(item["id"] == pending["runId"] for item in awaiting)
            event_params = {"enterpriseId": event_company["id"], "projectId": project["id"]}
            assert request("GET", "/api/leads", params=event_params).json()["total"] == 0
            channel.basic_publish(exchange="", routing_key=queue, body=pending_body, properties=pending_properties)
            assert consume_one(broker, base, login["token"], queue=queue)["runId"] == pending["runId"]
            decision = events(request("POST", f"/api/agent/tasks/{pending['runId']}/decision", json={"approved": True}).text)
            pending_lead = next(data["leadId"] for kind, data in decision if kind == "lead_created")
            assert request("GET", f"/api/leads/{pending_lead}").json()["leadStatus"] == "PENDING_REVIEW"
            assert request("GET", "/api/leads", params=event_params).json()["total"] == 1
            for name in (queue, queue + ".retry", queue + ".failed"):
                channel.queue_delete(queue=name)
        finally:
            connection.close()
        request("POST", "/api/auth/logout")
    print("PASS: demo monitor -> isolated chat -> review -> lead -> follow-up; RabbitMQ event -> Agent -> idempotent replay")


if __name__ == "__main__":
    main()
