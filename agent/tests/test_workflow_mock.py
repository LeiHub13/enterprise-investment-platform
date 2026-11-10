"""Offline workflow test; all enterprise records and model output are test fixtures."""
import json
from datetime import date
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app import config, graph, runtime
from app.main import app


def test_confirmation_is_required_before_pending_lead_creation(monkeypatch, tmp_path):
    created = []
    company = {"id": 12, "name": "测试企业", "cityCode": "320100", "districtCode": "320106",
               "industryName": "软件", "uscc": "TEST", "businessStatus": "存续"}
    signal = {"id": 13, "signalType": "RENTAL", "sourceRecordId": 14, "sourceName": "MANUAL",
              "validityStatus": "VALID", "publishedAt": date.today().isoformat(), "areaMin": 120, "areaMax": 200}

    class Client:
        def __init__(self, token, writer=None):
            assert token == "Bearer test-token"
        def close(self): pass
        def regions(self):
            return [{"name": "南京市", "level": "city", "code": "320100"},
                    {"name": "鼓楼区", "level": "district", "code": "320106", "parentCode": "320100"}]
        def get_building_info(self, node, project_id): return {"id": project_id, "areaMin": 100, "areaMax": 300}
        def search_company(self, node, **kwargs): return {"items": [company]}
        def get_company_detail(self, node, company_id): return company
        def search_company_signals(self, node, company_id): return [signal]
        def check_duplicate_lead(self, node, enterprise_id, project_id): return {"items": []}
        def create_pending_lead(self, node, payload):
            created.append(payload)
            return {"id": 99}

    class Model:
        def invoke(self, messages):
            if "需求解析器" in messages[0].content:
                return SimpleNamespace(content=json.dumps({"city": "南京", "district": "鼓楼", "industry_keywords": ["软件"],
                                                           "area_min": 100, "area_max": 300}, ensure_ascii=False))
            return SimpleNamespace(content="测试报告：求租信息需人工核实。")

    monkeypatch.setattr(config, "CHECKPOINT_POSTGRES_URI", "")
    monkeypatch.setattr(config, "CHECKPOINT_DB", str(tmp_path / "checkpoint.sqlite"))
    monkeypatch.setattr(graph, "BusinessClient", Client)
    monkeypatch.setattr(graph, "make_llm", Model)
    monkeypatch.setattr(graph, "analyze_with_supervisor", lambda *args: {"12": {"needs_verification": []}})
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client:
        response = client.post("/agent/chat", headers=headers, json={
            "runId": "test-run", "conversationId": "test-conversation", "projectId": 1,
            "message": "寻找南京鼓楼区有求租需求的软件企业",
        })
        assert response.status_code == 200
        assert "review_required" in response.text and "AWAITING_REVIEW" in response.text
        assert created == []
        assert runtime.get_token("test-run") == ""
        response = client.post("/agent/tasks/test-run/decision", headers=headers, json={"approved": True})
        assert response.status_code == 200 and "lead_created" in response.text and "DONE" in response.text
        assert created == [{"runId": "test-run", "enterpriseId": 12, "projectId": 1, "signalIds": [13]}]
    assert runtime.get_token("test-run") == ""
