"""Conservative entity association: ambiguity or conflicting credit codes never merge."""
import re
import unicodedata
from urllib.parse import urlsplit


def normalize_name(value: str) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value or ""))


def _credit(value) -> str:
    return str(value or "").strip().upper()


def _domain(value) -> str:
    text = str(value or "").strip().lower()
    if not text:
        return ""
    try:
        parsed = urlsplit(text if "://" in text else "https://" + text)
        if parsed.scheme not in ("http", "https") or parsed.username or parsed.password:
            return ""
        return (parsed.hostname or "").rstrip(".")
    except ValueError:
        return ""


def resolve_entity(facts: dict, candidates: list[dict], *, complete: bool = True) -> dict:
    """Resolve only against a complete authorized candidate set; never merge on name similarity."""
    credit = _credit(facts.get("uscc"))
    if credit:
        if not re.fullmatch(r"[0-9A-Z]{18}", credit):
            return {"status": "PENDING", "reason": "统一社会信用代码格式待核实", "match": None}
        matches = [item for item in candidates if _credit(item.get("uscc")) == credit]
        return ({"status": "MATCH", "reason": "统一社会信用代码一致", "match": matches[0]}
                if len(matches) == 1 and complete else {"status": "PENDING", "reason": "信用代码未找到唯一档案或候选结果不完整", "match": None})
    name = normalize_name(str(facts.get("name") or ""))
    matches = [item for item in candidates if name and normalize_name(str(item.get("name") or "")) == name]
    if len(matches) == 1 and complete:
        return {"status": "MATCH", "reason": "企业全称唯一精确匹配，主体仍需工商核验", "match": matches[0]}
    if matches or not complete:
        return {"status": "PENDING", "reason": "同名或候选结果不完整，无法确认唯一主体", "match": None}
    domain = _domain(facts.get("website"))
    address = normalize_name(str(facts.get("registeredAddress") or ""))
    domain_matches = [item for item in candidates if domain and _domain(item.get("website")) == domain]
    # A headquarters and its branch may share both address and website. Never
    # override contradictory full legal names with those weaker indicators.
    corroborated = [item for item in domain_matches
                    if address and address == normalize_name(str(item.get("registeredAddress") or ""))
                    and (not name or normalize_name(str(item.get("name") or "")) == name)]
    if len(corroborated) == 1 and complete:
        return {"status": "MATCH", "reason": "官网域名和注册地址一致，仍需核验主体", "match": corroborated[0]}
    return {"status": "PENDING", "reason": "无足够证据确认同一主体", "match": None}
