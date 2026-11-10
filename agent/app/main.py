import json
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.types import Command
from pydantic import BaseModel, Field

from . import config, runtime
from .graph import NODE_LABELS, build_graph


@asynccontextmanager
async def lifespan(app: FastAPI):
    if config.CHECKPOINT_POSTGRES_URI:
        async with AsyncPostgresSaver.from_conn_string(config.CHECKPOINT_POSTGRES_URI) as saver:
            await saver.setup()
            app.state.graph = build_graph(saver)
            yield
    else:
        async with AsyncSqliteSaver.from_conn_string(config.CHECKPOINT_DB) as saver:
            app.state.graph = build_graph(saver)
            yield


app = FastAPI(title="招商 Agent 服务", lifespan=lifespan)


class ChatBody(BaseModel):
    runId: str = Field(max_length=36)
    conversationId: str = Field(max_length=64)
    projectId: int
    message: str = Field(max_length=4000)
    source: str = "INTERNAL"
    history: list[dict[str, str]] = Field(default_factory=list, max_length=4)


class DecisionBody(BaseModel):
    approved: bool


class EventBody(BaseModel):
    runId: str = Field(max_length=36)
    enterpriseId: int
    recordId: int
    source: str = "QCC"
    recordType: str = "RECRUITMENT"
    title: str = Field(default="", max_length=4000)


def sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False, default=str)}\n\n"


async def stream_run(graph, payload, config_run: dict, run_id: str):
    try:
        meta = {"runId": run_id, "threadId": config_run["configurable"]["thread_id"]}
        if isinstance(payload, dict) and payload.get("conversation_id"):
            meta["conversationId"] = payload["conversation_id"]
        yield sse("meta", meta)
        async for mode, chunk in graph.astream(payload, config_run, stream_mode=["updates", "custom"]):
            if mode == "custom":
                yield sse(chunk["event"], chunk["data"])
            else:
                for node_name in chunk:
                    if node_name in NODE_LABELS:
                        yield sse("node", {"node": node_name, "label": NODE_LABELS[node_name], "status": "done"})
        state = await graph.aget_state(config_run)
        values = state.values or {}
        if state.next:
            yield sse("done", {"status": "AWAITING_REVIEW"})
        else:
            status = "REJECTED" if values.get("approved") is False else "DONE"
            yield sse("done", {"status": status})
    except Exception as error:  # noqa: BLE001 - SSE 边界统一转错误事件
        yield sse("error", {"message": f"Agent 执行失败：{error}"})
    finally:
        # A resume request carries a fresh authorization header; never retain it while waiting for review.
        runtime.pop_token(run_id)


@app.get("/health")
def health():
    return {"ready": True, "llmKeyConfigured": bool(config.LLM_API_KEY), "model": config.LLM_MODEL,
            "checkpoint": "postgres" if config.CHECKPOINT_POSTGRES_URI else "sqlite"}


@app.post("/agent/chat")
async def chat(body: ChatBody, request: Request):
    token = request.headers.get("authorization", "")
    runtime.set_token(body.runId, token)
    config_run = {"configurable": {"thread_id": body.runId}}
    payload = {
        "run_id": body.runId,
        "conversation_id": body.conversationId,
        "history": body.history,
        "project_id": body.projectId,
        "message": body.message,
        "source": "EXTERNAL" if body.source == "EXTERNAL" else "INTERNAL",
        "lead_ids": [],
        "errors": [],
    }
    return StreamingResponse(stream_run(request.app.state.graph, payload, config_run, body.runId),
                             media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/agent/events")
async def events(body: EventBody, request: Request):
    token = request.headers.get("authorization", "")
    runtime.set_token(body.runId, token)
    config_run = {"configurable": {"thread_id": body.runId}}
    payload = {
        "run_id": body.runId,
        "mode": "EVENT",
        "enterprise_id": body.enterpriseId,
        "record_id": body.recordId,
        "record_type": body.recordType,
        "message": body.title,
        "source": "INTERNAL",
        "lead_ids": [],
        "errors": [],
    }
    return StreamingResponse(stream_run(request.app.state.graph, payload, config_run, body.runId),
                             media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/agent/tasks/{run_id}/decision")
async def decision(run_id: str, body: DecisionBody, request: Request):
    token = request.headers.get("authorization", "")
    runtime.set_token(run_id, token)
    config_run = {"configurable": {"thread_id": run_id}}
    state = await request.app.state.graph.aget_state(config_run)
    if not state.next:
        runtime.pop_token(run_id)
        return JSONResponse({"message": "任务不在可恢复的审核状态"}, status_code=409)
    return StreamingResponse(
        stream_run(request.app.state.graph, Command(resume={"approved": body.approved}), config_run, run_id),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
