import os
from pathlib import Path
from urllib.parse import quote

BASE_DIR = Path(__file__).resolve().parent
_env_file = BASE_DIR / ".env"
if os.environ.get("COLLECTOR_SKIP_ENV_FILE") != "1" and _env_file.is_file():
    for _line in _env_file.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if _line and not _line.startswith("#") and "=" in _line:
            _key, _value = _line.split("=", 1)
            os.environ.setdefault(_key.strip(), _value.strip())

BUSINESS_BASE_URL = os.environ.get("BUSINESS_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
COLLECTOR_TOKEN = os.environ.get("COLLECTOR_TOKEN", "")
QCC_APP_KEY = os.environ.get("QCC_APP_KEY", "")
QCC_SECRET_KEY = os.environ.get("QCC_SECRET_KEY", "")
QCC_VERIFY_ON_MONITOR = os.environ.get("QCC_VERIFY_ON_MONITOR", "").strip().lower() in {"1", "true", "yes"}
QCC_RECRUITMENT_URL = os.environ.get(
    "QCC_RECRUITMENT_URL", "https://api.qichacha.com/Recruitment/GetList")
QCC_SEARCH_PARAM = os.environ.get("QCC_SEARCH_PARAM", "searchKey")
DEFAULT_INTERVAL_HOURS = float(os.environ.get("COLLECTOR_INTERVAL_HOURS", "24"))

# Compose can supply broker components separately; URL-encode credentials and
# vhost rather than interpolating them unsafely into an AMQP URI.
if not os.environ.get("RABBITMQ_URL") and all(os.environ.get(key) for key in ("RABBITMQ_HOST", "RABBITMQ_USER", "RABBITMQ_PASSWORD")):
    user = quote(os.environ["RABBITMQ_USER"], safe="")
    password = quote(os.environ["RABBITMQ_PASSWORD"], safe="")
    vhost = quote(os.environ.get("RABBITMQ_VHOST", "/"), safe="")
    os.environ["RABBITMQ_URL"] = f"amqp://{user}:{password}@{os.environ['RABBITMQ_HOST']}:{os.environ.get('RABBITMQ_PORT', '5672')}/{vhost}"
