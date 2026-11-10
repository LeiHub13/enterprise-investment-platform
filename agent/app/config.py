import os
from pathlib import Path
from urllib.parse import quote

# httpx 无法解析 NO_PROXY 中的 "[::1]" 形式，会导致 "Invalid port" 崩溃
for _key in ("NO_PROXY", "no_proxy"):
    if _key in os.environ:
        os.environ[_key] = (os.environ[_key]
                            .replace(",[::1]", "")
                            .replace("[::1],", "")
                            .replace("[::1]", ""))

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
DATA_DIR.mkdir(exist_ok=True)

_env_file = BASE_DIR / ".env"
if _env_file.is_file():
    for _line in _env_file.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if _line and not _line.startswith("#") and "=" in _line:
            _key, _value = _line.split("=", 1)
            os.environ.setdefault(_key.strip(), _value.strip())

BUSINESS_BASE_URL = os.environ.get("BUSINESS_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
LLM_BASE_URL = os.environ.get("DEEPSEEK_BASE_URL") or os.environ.get("LLM_BASE_URL", "https://api.deepseek.com/v1")
LLM_MODEL = os.environ.get("DEEPSEEK_MODEL") or os.environ.get("LLM_MODEL", "deepseek-chat")
LLM_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
CHECKPOINT_DB = str(DATA_DIR / "checkpoints.sqlite")
CHECKPOINT_POSTGRES_URI = os.environ.get("AGENT_CHECKPOINT_POSTGRES_URI", "").strip()
if not CHECKPOINT_POSTGRES_URI and os.environ.get("AGENT_POSTGRES_HOST"):
    _user = quote(os.environ.get("AGENT_POSTGRES_USER", "agent"), safe="")
    _password = quote(os.environ.get("AGENT_POSTGRES_PASSWORD", ""), safe="")
    _database = quote(os.environ.get("AGENT_POSTGRES_DB", "agent_checkpoints"), safe="")
    _host = os.environ["AGENT_POSTGRES_HOST"]
    _port = int(os.environ.get("AGENT_POSTGRES_PORT", "5432"))
    CHECKPOINT_POSTGRES_URI = f"postgresql://{_user}:{_password}@{_host}:{_port}/{_database}"
PORT = int(os.environ.get("AGENT_PORT", "8766"))
