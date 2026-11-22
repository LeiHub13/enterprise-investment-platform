import json
import time

import httpx

from . import config, runtime


class BusinessClient:
    """Agent 不直连业务库：全部工具经 Spring Boot API，携带用户 Token 继承其权限与数据范围。"""

    def __init__(self, token: str, writer=None):
        self._writer = writer
        self._client = httpx.Client(
            base_url=config.BUSINESS_BASE_URL,
            headers={"Authorization": token},
            timeout=30,
            trust_env=False,
        )

    def close(self):
        self._client.close()

    def _emit(self, node: str, tool: str, args: dict, result, status: str, started: float):
        if self._writer is None:
            return
        summary = json.dumps(result, ensure_ascii=False, default=str)
        self._writer({
            "event": "tool",
            "data": {
                "node": node,
                "tool": tool,
                "argsSummary": json.dumps(args, ensure_ascii=False, default=str)[:500],
                "resultSummary": summary[:500],
                "status": status,
                "durationMs": int((time.monotonic() - started) * 1000),
            },
        })

    def _call(self, node: str, tool: str, method: str, path: str, args: dict, payload=None):
        started = time.monotonic()
        try:
            response = self._client.request(method, path, params=args if method == "GET" else None,
                                            json=payload if method != "GET" else None)
            response.raise_for_status()
            result = response.json() if response.content else None
            self._emit(node, tool, args if method == "GET" else (payload or {}), result, "OK", started)
            return result
        except httpx.HTTPError as error:
            self._emit(node, tool, args, {"error": str(error)}, "ERROR", started)
            raise

    def regions(self):
        return self._call("search_companies", "get_regions", "GET", "/api/regions", {})

    def search_company(self, node: str, **filters):
        return self._call(node, "search_company", "GET", "/api/enterprises", filters)

    def get_company_detail(self, node: str, company_id: int):
        return self._call(node, "get_company_detail", "GET", f"/api/enterprises/{company_id}", {})

    def search_company_signals(self, node: str, company_id: int):
        return self._call(node, "search_company_signals", "GET", f"/api/enterprises/{company_id}/signals", {})

    def get_building_info(self, node: str, project_id: int):
        return self._call(node, "get_building_info", "GET", f"/api/projects/{project_id}", {})

    def check_duplicate_lead(self, node: str, enterprise_id: int, project_id: int):
        return self._call(node, "check_duplicate_lead", "GET", "/api/leads",
                          {"enterpriseId": enterprise_id, "projectId": project_id, "size": 1})

    def create_pending_lead(self, node: str, payload: dict):
        return self._call(node, "create_pending_lead", "POST", "/api/agent/leads/pending", {}, payload)

    def list_projects(self, node: str):
        result = self._call(node, "list_projects", "GET", "/api/projects",
                            {"projectStatus": "ACTIVE", "page": 1, "size": 100})
        return (result or {}).get("items") or []

