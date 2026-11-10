"""Deterministic, evidence-aware investment scoring; models never choose the scores."""
import json
import math
import os
from datetime import date

from .scoring import resolve_region


DEFAULT_WEIGHTS = {
    "priority": {"fit": 0.45, "intent": 0.40, "confidence": 0.15},
    "fit": {"area": 0.30, "region": 0.30, "industry": 0.20, "budget": 0.10, "scale": 0.10},
    "intent": {"rental": 0.80, "recruitment": 0.20},
    "confidence": {"verification": 0.30, "source": 0.20, "freshness": 0.20,
                   "completeness": 0.15, "corroboration": 0.15},
}


def scoring_weights() -> dict:
    """Optional JSON configuration replaces whole weight groups and must add up to one."""
    raw = os.environ.get("AGENT_SCORE_WEIGHTS", "").strip()
    override = json.loads(raw) if raw else {}
    if not isinstance(override, dict) or set(override) - set(DEFAULT_WEIGHTS):
        raise ValueError("AGENT_SCORE_WEIGHTS 含未知评分组")
    result = {}
    for group, defaults in DEFAULT_WEIGHTS.items():
        weights = override.get(group, defaults)
        if not isinstance(weights, dict) or set(weights) != set(defaults):
            raise ValueError(f"评分权重 {group} 必须包含且仅包含 {', '.join(defaults)}")
        if any(isinstance(value, bool) or not isinstance(value, (int, float))
               or not math.isfinite(value) or value < 0 for value in weights.values()):
            raise ValueError(f"评分权重 {group} 必须是非负有限数字")
        if not math.isclose(sum(weights.values()), 1, abs_tol=1e-9):
            raise ValueError(f"评分权重 {group} 之和必须为 1")
        result[group] = weights
    return result


def _number(value):
    try:
        number = float(value)
        return number if math.isfinite(number) and number > 0 else None
    except (TypeError, ValueError):
        return None


def _date(value):
    try:
        return date.fromisoformat(str(value)[:10]) if value else None
    except ValueError:
        return None


def _demo(signal):
    return str(signal.get("isDemo", signal.get("is_demo", False))).strip().lower() in {"true", "1", "yes"}


def _signal_date(signal):
    return _date(signal.get("publishedAt") or signal.get("observedAt") or signal.get("observed_at"))


def _current(signal, today, window):
    observed = _signal_date(signal)
    expiry_value = signal.get("expiresAt") or signal.get("expires_at")
    expiry = _date(expiry_value)
    return (not _demo(signal) and signal.get("validityStatus") != "EXPIRED"
            and (not signal.get("sourceRecordId") or signal.get("validityStatus") == "VALID")
            and observed is not None and 0 <= (today - observed).days <= window
            and (not expiry_value or (expiry is not None and expiry >= today)))


def _bounds(obj, prefix=""):
    low = _number(obj.get(prefix + "AreaMin" if prefix else "areaMin"))
    high = _number(obj.get(prefix + "AreaMax" if prefix else "areaMax"))
    if low is None and high is None:
        return None
    low, high = low or high, high or low
    return (low, high) if low <= high else None


def score_company(company: dict, signals: list, analysis: dict, requirements: dict,
                  region_tree: list, building: dict | None = None, *, today: date | None = None) -> dict:
    weights = scoring_weights()
    today = today or date.today()
    building = building or {}
    reasons = []
    scores = {}

    current = [s for s in (signals or []) if _current(s, today, 30)]
    evidence_ids = [s.get("id") for s in current if s.get("id") and s.get("sourceRecordId")
                    and s.get("validityStatus") == "VALID"
                    and str(s.get("signalType") or "").upper() in {"RECRUITMENT", "RENTAL"}]
    rentals = [s for s in current if str(s.get("signalType") or "").upper() == "RENTAL"
               and s.get("sourceRecordId") and s.get("validityStatus") == "VALID"]
    recruitment = [s for s in current if str(s.get("signalType") or "").upper() == "RECRUITMENT"
                   and (s.get("sourceRecordId") or s.get("sourceUrl"))]

    target_area = _bounds(building) or _bounds(building, "target")
    demand_area = next((_bounds(s) for s in rentals if _bounds(s)), None) or _bounds(company, "demand")
    area_matches = None
    if target_area and demand_area:
        area_matches = max(target_area[0], demand_area[0]) <= min(target_area[1], demand_area[1])
        scores["area"] = 100 if area_matches else 0
        reasons.append("面积：求租范围与楼宇目标范围" + ("有交集" if area_matches else "无交集"))
    else:
        reasons.append("待核实：缺少企业求租面积或楼宇面积，面积不参与匹配分计算")

    city_code = resolve_region(region_tree, requirements.get("city") or "", "city") or building.get("cityCode")
    district_code = resolve_region(region_tree, requirements.get("district") or "", "district", city_code)
    if city_code and company.get("cityCode"):
        if company.get("cityCode") != city_code:
            scores["region"] = 20
        elif district_code and company.get("districtCode") == district_code:
            scores["region"] = 80
        else:
            scores["region"] = 60
        reasons.append("区域：按企业注册地作辅助判断，实际求租区域仍需核实")
    else:
        reasons.append("待核实：区域信息不足，区域不参与匹配分计算")

    keywords = [str(k) for k in (requirements.get("industry_keywords") or []) if k]
    industry_text = f"{company.get('industryName') or ''} {company.get('businessScope') or ''}".strip()
    if keywords and industry_text:
        scores["industry"] = 100 if any(k in industry_text for k in keywords) else 0
        reasons.append("行业：按企业档案与目标行业关键词计算")
    else:
        reasons.append("待核实：行业未限定或档案缺失，行业不参与匹配分计算")

    budget = _number(company.get("budgetPerSqmDay"))
    rent = _number(building.get("rentPrice"))
    if budget and rent:
        scores["budget"] = 100 if budget >= rent else 0
        reasons.append("租金：按同单位的企业预算与楼宇租金比较")
    else:
        reasons.append("待核实：预算或租金未知，预算不参与匹配分计算")

    facts = company.get("_facts") or {}
    headcount = _number(company.get("headcount") or facts.get("headcount"))
    if headcount and target_area:
        estimated_area = headcount * 8
        scores["scale"] = 100 if target_area[0] <= estimated_area <= target_area[1] else 30
        reasons.append(f"规模：按每人 8㎡ 粗估约 {estimated_area:g}㎡，人数和实际面积需核实")
    else:
        reasons.append("待核实：人数或楼宇面积未知，规模不参与匹配分计算")

    known_weight = sum(weights["fit"][key] for key in scores)
    fit = round(sum(weights["fit"][key] * value for key, value in scores.items()) / known_weight) if known_weight else 50
    if not known_weight:
        reasons.append("匹配资料不足，使用中性基线 50 分")

    rental_value = 0
    if rentals:
        most_recent = max(_signal_date(s) for s in rentals)
        rental_value = 100 if (today - most_recent).days <= 7 else 80
        if area_matches is False:
            rental_value = round(rental_value * 0.7)
        reasons.append(f"明确求租：存在 {len(rentals)} 条近 30 天有效来源记录，仍需核实真实性")
    else:
        reasons.append("未知租赁意向：没有近 30 天可追溯的有效求租记录")
    recruitment_value = min(100, len(recruitment) * 50)
    if recruitment:
        reasons.append(f"间接扩张信号：存在 {len(recruitment)} 条近 30 天招聘记录；招聘不等于租赁需求")
    for evidence in (rentals + recruitment)[:5]:
        reasons.append(f"来源：{evidence.get('sourceName') or '待核实'}；"
                       f"发布时间：{evidence.get('publishedAt') or evidence.get('observedAt') or '未知'}；"
                       f"记录：{str(evidence.get('title') or '无标题')[:200]}")
    intent = round(weights["intent"]["rental"] * rental_value + weights["intent"]["recruitment"] * recruitment_value)

    sources = {str(s.get("sourceName") or "").strip().upper() for s in current
               if s.get("sourceRecordId") and s.get("sourceName")}
    verified = str(company.get("verificationStatus") or "").upper() == "VERIFIED"
    completeness = sum(bool(company.get(key)) for key in ("name", "uscc", "businessStatus")) / 3 * 100
    independent_sources = {str(s.get("sourceName") or "").upper() for s in current
                           if s.get("sourceRecordId") and s.get("sourceName") and s.get("independentSource") is True}
    components = {"verification": 100 if verified else 0, "source": 100 if sources else 0,
                  "freshness": 100 if sources else 0, "completeness": completeness,
                  "corroboration": 100 if len(independent_sources) >= 2 else 0}
    confidence = round(sum(weights["confidence"][key] * value for key, value in components.items()))
    if verified:
        reasons.append(f"主体核验：企查查授权结果在有效期内；核验时间：{company.get('verificationAt') or '待核实'}")
    else:
        reasons.append("待核实：工商主体尚无有效的授权核验结果")
    if len(independent_sources) < 2:
        reasons.append("待核实：缺少两个独立来源的交叉验证")
    priority = round(weights["priority"]["fit"] * fit + weights["priority"]["intent"] * intent
                     + weights["priority"]["confidence"] * confidence)
    pending = analysis.get("needs_verification")
    if isinstance(pending, list):
        for item in pending[:10]:
            if isinstance(item, str):
                reasons.append(f"待核实：{item[:500]}")
    blocked = (str(company.get("verificationStatus") or "").upper() in {"INACTIVE", "NOT_FOUND", "MISMATCH"}
               or any(status in str(company.get("businessStatus") or "") for status in ("注销", "吊销", "停业", "清算", "迁出")))
    if blocked:
        reasons.append("主体核验存在异常或不一致，暂缓创建线索并需人工核实")
    return {
        "enterpriseId": company.get("id"), "enterpriseName": company.get("name"),
        "priorityScore": priority, "fitScore": fit, "intentScore": intent, "confidenceScore": confidence,
        "totalScore": priority, "grade": "HOLD" if blocked else "PRIORITY" if priority >= 80 else "VERIFY" if priority >= 60 else "HOLD",
        "reasons": reasons, "evidenceSignalIds": list(dict.fromkeys(evidence_ids))[:50],
    }
