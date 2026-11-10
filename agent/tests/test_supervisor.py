from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage, HumanMessage
import pytest

from datetime import date, timedelta

from app.supervisor import build_supervisor, recruitment_stats


class ToolCallingFakeModel(FakeMessagesListChatModel):
    def bind_tools(self, tools, **kwargs):
        return self


def call(name, arguments, call_id):
    return AIMessage(content="", tool_calls=[{"name": name, "args": arguments, "id": call_id}])


@pytest.mark.parametrize(("specialist", "tool_name", "signal_type"), [
    ("recruitment-analyst", "query_recruitment", "RECRUITMENT"),
    ("office-rental-analyst", "search_office_rentals", "RENTAL"),
])
def test_supervisor_delegates_to_real_specialist_tool(specialist, tool_name, signal_type):
    main_model = ToolCallingFakeModel(responses=[
        call("task", {"description": "查询企业 12 的证据", "subagent_type": specialist}, "task-1"),
        AIMessage(content='{"12":{"needs_verification":[]}}'),
    ])
    child_model = ToolCallingFakeModel(responses=[
        call(tool_name, {"company_id": 12}, "child-1"),
        AIMessage(content="已读取来源记录，仍需核实。"),
    ])
    events = []
    enriched = [{"company": {"id": 12, "name": "测试企业"},
                 "signals": [{"signalType": signal_type, "title": "测试记录", "sourceName": "测试来源"}]}]
    supervisor = build_supervisor(enriched, main_model, events.append, specialist_model=child_model)
    result = supervisor.invoke({"messages": [HumanMessage(content="查询企业 12")]})
    assert result["messages"][-1].content.startswith('{"12"')
    logs = [event["data"] for event in events if event["event"] == "tool"]
    assert len(logs) == 1 and logs[0]["tool"] == tool_name
    assert "测试来源" in logs[0]["resultSummary"]


def test_supervisor_need_not_invoke_specialists():
    main_model = ToolCallingFakeModel(responses=[AIMessage(content="{}")])
    events = []
    supervisor = build_supervisor([], main_model, events.append)
    supervisor.invoke({"messages": [HumanMessage(content="没有需要补充的数据")]})
    assert events == []


def test_job_statistics_deduplicate_snapshots_and_exclude_demo_or_undated():
    today = date(2026, 11, 9)
    job = {"externalId": "1", "title": "开发", "publishedAt": (today - timedelta(days=2)).isoformat()}
    old = {"externalId": "2", "title": "测试", "publishedAt": (today - timedelta(days=12)).isoformat()}
    unknown = {"externalId": "3", "title": "设计", "publishedAt": ""}
    snapshot = {"signalType": "RECRUITMENT", "sourceRecordId": 1, "sourceName": "QCC",
                "validityStatus": "VALID", "recruitmentPosts": [job, old, unknown]}
    stats = recruitment_stats([snapshot, snapshot, {**snapshot, "isDemo": True}], today=today)
    assert stats["jobs7d"] == 1 and stats["jobs30d"] == 2 and stats["undatedJobs"] == 1
    assert recruitment_stats([{**snapshot, "isDemo": True}], today=today)["jobs30d"] is None
