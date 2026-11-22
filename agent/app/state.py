from typing import TypedDict


class InvestmentAgentState(TypedDict, total=False):
    run_id: str
    conversation_id: str
    history: list
    mode: str
    project_id: int
    message: str
    source: str
    enterprise_id: int
    record_id: int
    record_type: str
    verification: str
    verification_results: dict
    observation_id: int
    requirements: dict
    building: dict
    region_tree: list
    companies: list
    enriched: list
    analyses: dict
    scored: list
    candidates: list
    report: str
    question: str
    approved: bool
    lead_ids: list
    errors: list
