from datetime import date, timedelta
import json
from types import SimpleNamespace

import pytest

from app import graph
from app import config
from app.graph import _normalize_name, verify_enterprise
from app.llm import make_llm
from app.scoring import score_company


@pytest.mark.parametrize("signal", [
    {"signalType": "RECRUITMENT", "strength": 3},
    {"signalType": "RECRUITMENT", "strength": 3, "observedAt": date.today().isoformat(), "isDemo": True},
    {"signalType": "RENTAL", "strength": 3, "observedAt": date.today().isoformat(), "validityStatus": "EXPIRED"},
    {"signalType": "RENTAL", "strength": 3, "observedAt": date.today().isoformat(),
     "expiresAt": (date.today() - timedelta(days=1)).isoformat()},
    {"signalType": "RECRUITMENT", "strength": 3, "observedAt": (date.today() + timedelta(days=1)).isoformat()},
])
def test_unusable_signals_do_not_count_as_current(signal):
    result = score_company({"name": "待核验企业"}, [signal], {}, {}, [])
    assert result["signalScore"] == 0


def test_current_real_signal_counts_but_is_not_rental_proof():
    signal = {"signalType": "RECRUITMENT", "strength": 1, "observedAt": date.today().isoformat()}
    result = score_company({"name": "待核验企业"}, [signal], {}, {}, [])
    assert result["signalScore"] > 0
    assert any("不等于租赁意向" in reason for reason in result["reasons"])


def test_registry_code_alone_is_not_external_verification():
    state = {"enriched": [{"company": {"name": "待核验企业", "uscc": "91320000EXAMPLE00"}}]}
    assert verify_enterprise(state, None)["verification"] == "PENDING"


def test_candidate_registry_check_remains_pending_without_authorized_verification():
    events = []
    state = {"enriched": [{"company": {"id": 3, "name": "示例公司", "uscc": "913200000000000001", "businessStatus": "吊销"}}]}
    result = graph.verify_candidates(state, events.append)
    assert result["verification_results"]["3"]["status"] == "PENDING"
    assert "异常" in result["verification_results"]["3"]["reason"]
    assert any("尚未" in value["reason"] for value in result["verification_results"].values())


def test_matching_preserves_branch_and_parenthetical_company_names():
    assert _normalize_name("南京某公司（鼓楼分公司）") != _normalize_name("南京某公司")
    assert _normalize_name("南京某公司（鼓楼分公司）") == _normalize_name("南京某公司(鼓楼分公司)")


def test_followup_request_receives_previous_turns(monkeypatch):
    captured = []
    class Model:
        def invoke(self, messages):
            captured.append(json.loads(messages[1].content))
            return SimpleNamespace(content='{"city":"南京"}')
    monkeypatch.setattr(graph, "make_llm", Model)
    graph.parse_requirements({"message": "改为科技行业", "history": [{"message": "寻找南京企业", "report": "测试报告"}]}, None)
    assert captured[0]["current_request"] == "改为科技行业"
    assert captured[0]["history"][0]["message"] == "寻找南京企业"


def test_missing_deepseek_key_fails_before_network_call(monkeypatch):
    monkeypatch.setattr(config, "LLM_API_KEY", "")
    with pytest.raises(RuntimeError, match="DEEPSEEK_API_KEY"):
        make_llm()


def test_unapproved_event_observation_does_not_write_business_data(monkeypatch):
    def forbidden_client(*args):
        raise AssertionError("unapproved observation must not create a business client")
    monkeypatch.setattr(graph, "BusinessClient", forbidden_client)
    events = []
    result = graph.save_observation({"enriched": [{"company": {"id": 1, "name": "演示企业"}}]}, events.append)
    assert result["observation_id"] is None
    assert any(event["event"] == "observation" and event["data"]["saved"] is False for event in events)
