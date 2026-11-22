"""Optional integration test against an explicitly configured PostgreSQL checkpoint database."""
import asyncio
import os
import uuid
from typing import TypedDict

import pytest
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from app.loop import selector_loop


class ReviewState(TypedDict, total=False):
    approved: bool


def review(_: ReviewState) -> dict:
    return {"approved": bool(interrupt({"type": "review"}))}


def graph_for(saver):
    builder = StateGraph(ReviewState)
    builder.add_node("review", review)
    builder.add_edge(START, "review")
    builder.add_edge("review", END)
    return builder.compile(checkpointer=saver)


@pytest.mark.skipif(not os.environ.get("TEST_CHECKPOINT_POSTGRES_URI"), reason="no test PostgreSQL configured")
def test_interrupt_survives_reconnection():
    async def exercise():
        uri = os.environ["TEST_CHECKPOINT_POSTGRES_URI"]
        config = {"configurable": {"thread_id": "test-" + uuid.uuid4().hex}}
        async with AsyncPostgresSaver.from_conn_string(uri) as saver:
            await saver.setup()
            graph = graph_for(saver)
            await graph.ainvoke({}, config)
            assert (await graph.aget_state(config)).next == ("review",)
        async with AsyncPostgresSaver.from_conn_string(uri) as saver:
            graph = graph_for(saver)
            try:
                result = await graph.ainvoke(Command(resume=True), config)
                assert result["approved"] is True
                assert not (await graph.aget_state(config)).next
            finally:
                await saver.adelete_thread(config["configurable"]["thread_id"])

    asyncio.run(exercise(), loop_factory=selector_loop)
