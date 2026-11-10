"""Test-only OpenAI-compatible model for an isolated end-to-end stack.

This server never calls a third party and emits no real-world claims. Do not
point a production system at it; all records used with it must be test fixtures.
"""
import json
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def response(content=None, calls=None):
    message = {"role": "assistant", "content": content}
    if calls:
        message["tool_calls"] = [{"id": f"call_{i}", "type": "function",
                                  "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}
                                 for i, (name, args) in enumerate(calls, 1)]
    return {"id": "chatcmpl-isolated-test", "object": "chat.completion", "created": int(time.time()),
            "model": "isolated-test-model", "choices": [{"index": 0, "message": message,
            "finish_reason": "tool_calls" if calls else "stop"}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            messages = body.get("messages", [])
            tools = {item.get("function", {}).get("name") for item in body.get("tools", [])}
            system = " ".join(str(m.get("content") or "") for m in messages if m.get("role") == "system")
            user = next((str(m.get("content") or "") for m in reversed(messages) if m.get("role") == "user"), "")
            if "需求解析器" in system:
                request = json.loads(user)
                match = re.search(r"【自动化测试非真实】[0-9a-f]{32}", request["current_request"])
                if not match:
                    raise ValueError("missing isolated test company name")
                result = response(json.dumps({"city": "南京", "district": "鼓楼", "industry_keywords": ["软件"],
                    "external_companies": [{"name": match.group(0)}]}, ensure_ascii=False))
            elif "报告撰写器" in system:
                result = response("【自动化测试报告，非真实数据】来源和企业信息均为隔离测试夹具，需人工核实。")
            elif "企业招聘扩张分析器" in system or "企业办公求租分析器" in system:
                result = response('{}')
            elif "task" in tools:
                if messages[-1].get("role") == "tool":
                    result = response('{}')
                else:
                    request = json.loads(user)
                    company_id = request["candidates"][0]["id"]
                    result = response(calls=[("task", {"subagent_type": kind,
                        "description": f"请查询测试企业 ID={company_id} 的来源信息。"})
                        for kind in ("recruitment-analyst", "office-rental-analyst")])
            elif "query_recruitment" in tools or "search_office_rentals" in tools:
                if messages[-1].get("role") == "tool":
                    result = response("测试记录已读取，全部内容仅供隔离测试。")
                else:
                    match = re.search(r"ID\s*=\s*(\d+)", user)
                    if not match:
                        raise ValueError("missing test company id")
                    tool = "query_recruitment" if "query_recruitment" in tools else "search_office_rentals"
                    result = response(calls=[(tool, {"company_id": int(match.group(1))})])
            else:
                raise ValueError("unexpected test-model request")
            payload = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
        except Exception as error:
            payload = json.dumps({"error": str(error)}).encode("utf-8")
            self.send_response(400)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8999), Handler).serve_forever()
