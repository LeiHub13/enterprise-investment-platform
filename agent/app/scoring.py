"""规则评分引擎：行业25 区域20 规模20 信号25 完整度10（权重可配置，后续按转化数据调整）。"""
from datetime import date, timedelta

WEIGHTS = {"industry": 25, "region": 20, "scale": 20, "signal": 25, "completeness": 10}
SIGNAL_WINDOW_DAYS = 180


def resolve_region(tree: list, name: str, level: str, parent_code: str | None = None) -> str | None:
    """地区目录是扁平列表，重名区县跨城市存在；按层级+父级解析，容忍缺少后缀（南京→南京市）。"""
    if not name:
        return None
    entries = [item for item in tree or []
               if item.get("level") == level and (parent_code is None or item.get("parentCode") == parent_code)]
    for item in entries:
        if item.get("name") == name:
            return item.get("code")
    for suffix in ("市", "区", "县", "省"):
        for item in entries:
            if item.get("name") == name + suffix:
                return item.get("code")
    matches = [item for item in entries if item.get("name", "").startswith(name)]
    return matches[0].get("code") if matches else None


def score_company(company: dict, signals: list, analysis: dict, requirements: dict,
                  region_tree: list) -> dict:
    reasons = []
    keywords = requirements.get("industry_keywords") or []
    industry_text = f"{company.get('industryName') or ''} {company.get('businessScope') or ''}"
    if not keywords:
        industry = 15
        reasons.append("行业匹配：未限定行业，按通用办公需求计 15/25")
    elif any(k and k in industry_text for k in keywords):
        industry = 25
        reasons.append(f"行业匹配：经营范围/行业命中关键词 {'、'.join(keywords)}，25/25")
    else:
        industry = 5
        reasons.append(f"行业匹配：未命中关键词 {'、'.join(keywords)}，5/25")

    city_code = resolve_region(region_tree, requirements.get("city") or "", "city")
    district_code = resolve_region(region_tree, requirements.get("district") or "", "district", city_code)
    if company.get("cityCode") == city_code and city_code:
        region = 12
        if district_code and company.get("districtCode") == district_code:
            region = 20
            reasons.append(f"区域匹配：注册地在 {requirements.get('city')}{requirements.get('district')}，20/20")
        else:
            reasons.append(f"区域匹配：注册地在 {requirements.get('city')}，目标区县待核实，12/20")
    elif company.get("provinceCode") and city_code and str(company.get("provinceCode"))[:2] == str(city_code)[:2]:
        region = 6
        reasons.append("区域匹配：同省非目标城市，进入目标区域需求待核实，6/20")
    else:
        region = 0
        reasons.append("区域匹配：注册地不在目标城市，0/20")

    capital = company.get("registeredCapital")
    try:
        capital = float(capital) if capital is not None else None
    except (TypeError, ValueError):
        capital = None
    facts = company.get("_facts") or {}
    raw_headcount = company.get("headcount") or facts.get("headcount")
    try:
        headcount = int(raw_headcount) if raw_headcount not in (None, "") else None
    except (TypeError, ValueError):
        headcount = None
    area_min = requirements.get("area_min") or 0
    area_max = requirements.get("area_max") or 0
    scale = 10
    if headcount:
        need = headcount * 8
        if area_min and area_max and area_min <= need <= area_max:
            scale = 20
            reasons.append(f"规模匹配：按 {headcount} 人×8㎡ 估算约 {need}㎡，落在房源区间，20/20；人数为用户提供待核验")
        elif area_min and area_max and area_min * 0.7 <= need <= area_max * 1.3:
            scale = 12
            reasons.append(f"规模匹配：按 {headcount} 人×8㎡ 估算约 {need}㎡，与房源区间接近，12/20；人数待核验")
        elif area_min and area_max:
            scale = 4
            reasons.append(f"规模匹配：按 {headcount} 人×8㎡ 估算约 {need}㎡，与 {area_min}～{area_max}㎡ 房源区间差距较大，4/20；人数待核验")
        else:
            scale = 15
            reasons.append(f"规模匹配：按 {headcount} 人×8㎡ 估算约 {need}㎡，未提供目标面积区间，15/20；人数待核验")
    elif capital is not None and 5_000_000 <= capital <= 500_000_000:
        scale = 15
        reasons.append("规模匹配：注册资本处于中小办公需求区间，15/20；人数与面积需求待核实")
    else:
        reasons.append("规模匹配：缺少人数与办公面积数据，按待核实基线 10/20")

    cutoff = date.today() - timedelta(days=SIGNAL_WINDOW_DAYS)
    strength = 0
    for signal in signals or []:
        observed = signal.get("observedAt") or signal.get("observed_at")
        expires = signal.get("expiresAt") or signal.get("expires_at")
        is_demo = signal.get("isDemo") in (True, 1, "1", "true")
        try:
            observed_date = date.fromisoformat(str(observed)[:10]) if observed else None
            expires_date = date.fromisoformat(str(expires)[:10]) if expires else None
            fresh = (observed_date is not None and cutoff <= observed_date <= date.today()
                     and (expires_date is None or expires_date >= date.today())
                     and signal.get("validityStatus") != "EXPIRED" and not is_demo)
        except ValueError:
            fresh = False
        if fresh:
            strength += int(signal.get("strength") or 1)
    signal_score = 25 if strength >= 4 else 20 if strength == 3 else 14 if strength == 2 else 8 if strength == 1 else 0
    if signal_score:
        reasons.append(f"扩张信号：近 {SIGNAL_WINDOW_DAYS} 天信号强度合计 {strength}，{signal_score}/25；信号不等于租赁意向")
    else:
        reasons.append(f"扩张信号：近 {SIGNAL_WINDOW_DAYS} 天无登记信号，0/25")
    if analysis.get("expansion_evidence"):
        reasons.append(f"信号分析：{analysis['expansion_evidence']}")

    completeness = 0
    if all(company.get(k) for k in ("name", "uscc", "businessStatus", "industryName", "cityCode")):
        completeness += 6
    if company.get("website") or company.get("registeredAddress"):
        completeness += 2
    verified = company.get("lastVerifiedAt")
    if verified and str(verified)[:10] >= (date.today() - timedelta(days=365)).isoformat():
        completeness += 2
    reasons.append(f"信息完整度：{completeness}/10，联系方式与办公现状须人工核验")

    total = industry + region + scale + signal_score + completeness
    grade = "PRIORITY" if total >= 80 else "VERIFY" if total >= 60 else "HOLD"
    for item in analysis.get("needs_verification") or []:
        reasons.append(f"待核实：{item}")
    return {
        "enterpriseId": company.get("id"),
        "enterpriseName": company.get("name"),
        "totalScore": total,
        "industryScore": industry,
        "regionScore": region,
        "scaleScore": scale,
        "signalScore": signal_score,
        "completenessScore": completeness,
        "grade": grade,
        "reasons": reasons,
    }
