import json
import re

from langchain_openai import ChatOpenAI

from . import config


def make_llm() -> ChatOpenAI:
    if not config.LLM_API_KEY:
        raise RuntimeError("未配置 DEEPSEEK_API_KEY，无法执行真实模型对话。")
    return ChatOpenAI(
        model=config.LLM_MODEL,
        base_url=config.LLM_BASE_URL,
        api_key=config.LLM_API_KEY,
        temperature=0.2,
        timeout=90,
        max_retries=1,
    )


def parse_json(text: str, fallback):
    cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip(), flags=re.MULTILINE)
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", cleaned, flags=re.DOTALL)
        if match:
            try:
                return json.loads(match.group(0))
            except json.JSONDecodeError:
                return fallback
        return fallback
