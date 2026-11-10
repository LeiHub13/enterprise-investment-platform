"""用户 Token 仅存进程内存：checkpoint 会持久化 configurable，Token 不得进入其中。"""
TOKENS: dict[str, str] = {}


def set_token(run_id: str, token: str):
    TOKENS[run_id] = token


def pop_token(run_id: str):
    TOKENS.pop(run_id, None)


def get_token(run_id: str) -> str:
    return TOKENS.get(run_id, "")