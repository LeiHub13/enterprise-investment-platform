"""单 Agent + 多工具 + LangGraph 工作流：解析→校验→搜索→enrich→信号分析→评分→查重→报告→人工审核→建线索/跟进。"""
import json
import re
import unicodedata

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.config import get_config
from langgraph.graph import END, START, StateGraph
from langgraph.types import StreamWriter, interrupt

from . import runtime
from .entity_matching import resolve_entity
from .llm import make_llm, parse_json
from .scoring import resolve_region
from .priority_scoring import score_company
from .state import InvestmentAgentState
from .supervisor import analyze_with_supervisor
from .tools import BusinessClient

# LangGraph 节点 id → 前端展示用中文名称（SSE node 事件）
NODE_LABELS = {
    "parse_requirements": "理解招商需求",
    "validate_requirements": "校验筛选条件",
    "search_companies": "搜索企业资源库",
    "enrich_company": "补充企业档案与信号",
    "analyze_signals": "分析扩张信号",
    "calculate_score": "计算匹配评分",
    "deduplicate": "线索查重",
    "generate_report": "生成推荐报告",
    "human_review": "等待人工审核",
    "create_leads": "创建待审核线索",
    "load_event": "载入数据事件",
    "analyze_recruitment": "招聘扩张分析",
    "analyze_rental": "办公求租分析",
    "verify_enterprise": "企业核验",
    "associate_entity": "企业实体关联",
    "verify_candidates": "企业档案核查",
    "associate_candidates": "来源记录关联",
    "match_building": "楼宇匹配",
    "lead_condition": "线索条件判定",
    "save_observation": "生成观察结果",
}


def _emit(writer: StreamWriter | None, event: str, data: dict):
    """向流式 writer 推送自定义 SSE 事件。"""
    if writer is not None:
        writer({"event": event, "data": data})


def _node_start(writer: StreamWriter | None, name: str):
    """推送节点进入 running 状态的进度事件。"""
    _emit(writer, "node", {"node": name, "label": NODE_LABELS[name], "status": "running"})


def _client(writer: StreamWriter | None) -> BusinessClient:
    """按当前 thread/run 鉴权创建业务侧 HTTP 客户端。"""
    run_id = get_config()["configurable"]["thread_id"]
    return BusinessClient(runtime.get_token(run_id), writer)


def _ask(llm, system: str, user: str) -> str:
    """单次 LLM 对话，返回助手文本内容。"""
    return llm.invoke([SystemMessage(content=system), HumanMessage(content=user)]).content


def _normalize_name(value: str) -> str:
    """规范化企业名称用于比对（保留括号与分支信息，仅去空白与全半角）。"""
    # 保留括号及分支机构名称，不能将相似名称当成同一法律主体。
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value or ""))


def parse_requirements(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """理解招商需求：从对话解析城市、面积、行业及外部企业名单。"""
    _node_start(writer, "parse_requirements")
    llm = make_llm()
    source = state.get("source") or "INTERNAL"
    external_rule = (
        "用户会点名若干外部企业。除通用字段外，另输出 external_companies 数组，每项："
        "{name(企业全称), uscc(统一社会信用代码,可空), website(官网,可空), registeredAddress(注册地址,可空), "
        "industry(行业,可空), city(城市,可空), district(区县,可空), "
        "headcount(员工人数,数字,可空), notes(用户提供的线索,可空)}。只收录用户明确提到的企业，不得编造。"
        if source == "EXTERNAL" else "输出 external_companies 为空数组。"
    )
    text = _ask(
        llm,
        "你是招商需求解析器。只输出 JSON，字段：city(目标城市名), district(目标区县名,可为空), "
        "industry_keywords(行业关键词数组,可为空), area_min(面积下限㎡,数字), area_max(面积上限㎡,数字), "
        "prefer_expansion(布尔,是否优先扩张企业), external_companies(外部企业数组)。"
        "无法确定的字段给空值,不要编造。历史对话仅供理解当前请求，当前请求优先。" + external_rule,
        json.dumps({"history": state.get("history") or [], "current_request": state["message"]}, ensure_ascii=False),
    )
    req = parse_json(text, {})
    if not isinstance(req, dict):
        req = {}
    external = []
    supplied = req.get("external_companies")
    for item in (supplied if isinstance(supplied, list) else [])[:8]:
        if isinstance(item, dict) and str(item.get("name") or "").strip():
            external.append({
                "name": str(item["name"]).strip(),
                "uscc": str(item.get("uscc") or "").strip(),
                "website": str(item.get("website") or "").strip(),
                "registeredAddress": str(item.get("registeredAddress") or "").strip(),
                "industry": str(item.get("industry") or "").strip(),
                "city": str(item.get("city") or "").strip(),
                "district": str(item.get("district") or "").strip(),
                "headcount": item.get("headcount"),
                "notes": str(item.get("notes") or "").strip(),
            })
    return {"requirements": {
        "city": req.get("city") or "",
        "district": req.get("district") or "",
        "industry_keywords": req.get("industry_keywords") or [],
        "area_min": req.get("area_min") or 0,
        "area_max": req.get("area_max") or 0,
        "prefer_expansion": bool(req.get("prefer_expansion")),
        "external_companies": external,
    }}


def validate_requirements(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """校验筛选条件是否完整；缺失时写入 question 并提示用户补充。"""
    _node_start(writer, "validate_requirements")
    source = state.get("source") or "INTERNAL"
    if source == "EXTERNAL":
        if not state["requirements"].get("external_companies"):
            question = "外部企业模式未识别到企业名称，请写明企业全称与已知信息（行业、城市、人数等）后重新发送。"
            _emit(writer, "message", {"text": question})
            return {"question": question}
        return {}
    if not state["requirements"].get("city"):
        question = "需求中缺少目标城市，请补充城市（如：南京）后重新发送。"
        _emit(writer, "message", {"text": question})
        return {"question": question}
    return {}


def route_validate(state: InvestmentAgentState) -> str:
    """校验失败则结束，否则进入企业搜索。"""
    return END if state.get("question") else "search_companies"


def search_companies(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """搜索企业资源库：内部模式按城市检索，外部模式按用户点名企业匹配档案。"""
    _node_start(writer, "search_companies")
    client = _client(writer)
    try:
        tree = client.regions() or []
        req = state["requirements"]
        building = client.get_building_info("search_companies", state["project_id"])
        if state.get("source") == "EXTERNAL":
            companies = []
            for facts in req.get("external_companies") or []:
                name = facts["name"]
                keyword = facts.get("uscc") or name
                matched_items = client.search_company("search_companies", keyword=keyword, page=1, size=100)
                rows = matched_items.get("items") or []
                raw_total = matched_items.get("total")
                total = str(raw_total) if raw_total is not None else ""
                complete = total.isdigit() and int(total) <= len(rows)
                identity = resolve_entity(facts, rows, complete=complete)
                exact = identity.get("match")
                if exact:
                    companies.append({**exact, "_matched": True, "_facts": facts, "_identityReason": identity["reason"]})
                else:
                    city_code = resolve_region(tree, facts.get("city") or req.get("city") or "", "city")
                    companies.append({
                        "id": None, "name": name, "industryName": facts.get("industry") or "",
                        "provinceCode": None, "cityCode": city_code,
                        "districtCode": resolve_region(tree, facts.get("district") or "", "district", city_code),
                        "businessStatus": None, "uscc": facts.get("uscc") or None, "registeredCapital": None,
                        "registeredAddress": None, "website": None, "lastVerifiedAt": None,
                        "businessScope": "", "_matched": False, "_facts": facts, "_identityReason": identity["reason"],
                    })
            return {"companies": companies, "region_tree": tree, "building": building}
        city_code = resolve_region(tree, req["city"], "city")
        if not city_code:
            question = f"城市「{req['city']}」不在地区目录中，请检查城市名称。"
            _emit(writer, "message", {"text": question})
            return {"question": question, "region_tree": tree}
        result = client.search_company("search_companies", cityCode=city_code, page=1, size=50)
        companies = result.get("items") or []
        if not companies:
            question = f"{req['city']} 的企业资源库暂无数据，请先导入企业或调整城市。"
            _emit(writer, "message", {"text": question})
            return {"question": question, "region_tree": tree, "building": building}
        return {"companies": companies, "region_tree": tree, "building": building}
    finally:
        client.close()


def route_search(state: InvestmentAgentState) -> str:
    """搜索异常或无结果则结束，否则补充企业详情。"""
    return END if state.get("question") or not state.get("companies") else "enrich_company"


def enrich_company(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """拉取企业档案详情与关联扩张/招聘等信号。"""
    _node_start(writer, "enrich_company")
    client = _client(writer)
    enriched = []
    try:
        for company in state["companies"][:8]:
            if company.get("id") is None:
                enriched.append({"company": company, "signals": []})
                continue
            detail = client.get_company_detail("enrich_company", company["id"])
            signals = client.search_company_signals("enrich_company", company["id"]) or []
            enriched.append({"company": {**detail, "_matched": company.get("_matched", False),
                                         "_facts": company.get("_facts"),
                                         "_identityReason": company.get("_identityReason")}, "signals": signals})
    finally:
        client.close()
    return {"enriched": enriched}


def verify_candidates(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """汇总各候选企业的档案核验状态与经营状态警示。"""
    _node_start(writer, "verify_candidates")
    results = {}
    for item in state.get("enriched") or []:
        company = item["company"]
        key = str(company.get("id") or company.get("name") or "")
        status = str(company.get("businessStatus") or "")
        warning = "；档案经营状态存在异常，需进一步核实" if any(x in status for x in ("注销", "吊销", "停业", "清算", "迁出")) else ""
        verified = str(company.get("verificationStatus") or "PENDING").upper()
        note = ("企查查授权核验结果在有效期内，仍需关注后续变化" + warning) if verified == "VERIFIED" else (
            str(company.get("verificationReason") or "仅核对企业库档案，尚未获得有效授权核验结果") + warning)
        results[key] = {"status": verified, "reason": note}
    confirmed = sum(item["status"] == "VERIFIED" for item in results.values())
    _emit(writer, "message", {"text": f"企业档案核查：{confirmed} 家有有效企查查核验结果，其余需核实；不能仅凭信用代码认定已核验。"})
    return {"verification_results": results}


def associate_candidates(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """统计信号与来源业务记录的关联条数（不合并相似名称主体）。"""
    _node_start(writer, "associate_candidates")
    records = sum(1 for item in state.get("enriched") or [] for signal in item.get("signals") or []
                  if signal.get("sourceRecordId") and signal.get("validityStatus"))
    _emit(writer, "message", {"text": f"来源记录关联：按业务库中的企业 ID 读取 {records} 条关联信号；未凭相似名称合并主体。"})
    return {}


def analyze_signals(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """调用 supervisor 分析扩张信号与待核实项。"""
    _node_start(writer, "analyze_signals")
    analyses = analyze_with_supervisor(state["enriched"], state.get("message") or "",
                                      state.get("requirements") or {}, make_llm(), writer)
    return {"analyses": analyses}


def calculate_score(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """结合需求、信号与分析结果计算匹配评分并排序。"""
    _node_start(writer, "calculate_score")
    source = state.get("source") or "INTERNAL"
    scored = []
    for item in state["enriched"]:
        company = item["company"]
        raw_analysis = state.get("analyses", {}).get(str(company.get("id")))
        analysis = dict(raw_analysis) if isinstance(raw_analysis, dict) else {}
        if source == "EXTERNAL":
            facts = company.get("_facts") or {}
            tips = ["外部企业：用户提供的信息需单独核实"]
            if company.get("_identityReason"):
                tips.append("主体关联：" + company["_identityReason"])
            if facts.get("headcount"):
                tips.append(f"用户提供员工约 {facts['headcount']} 人，须核验")
            if facts.get("notes"):
                tips.append(f"用户线索：{facts['notes']}")
            analysis["needs_verification"] = list(analysis.get("needs_verification") or []) + tips
        entry = score_company(company, item["signals"], analysis,
                              state["requirements"], state.get("region_tree") or [], state.get("building"))
        entry["sourceType"] = source
        if source == "EXTERNAL" and company.get("id"):
            entry["matchedEnterpriseId"] = company.get("id")
        if source == "EXTERNAL" and not company.get("id"):
            entry["enterpriseId"] = None
        scored.append(entry)
    scored.sort(key=lambda s: s["totalScore"], reverse=True)
    return {"scored": scored}


def deduplicate(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """对话模式：查重已有线索并筛出最多 5 家可创建线索的候选。"""
    _node_start(writer, "deduplicate")
    client = _client(writer)
    candidates = []
    try:
        for entry in state["scored"]:
            entry["projectId"] = state["project_id"]
            entry["duplicateLeadId"] = None
            if entry["grade"] != "HOLD" and entry.get("enterpriseId") and entry.get("evidenceSignalIds"):
                existing = client.check_duplicate_lead("deduplicate", entry["enterpriseId"], state["project_id"])
                items = existing.get("items") or []
                if items:
                    entry["duplicateLeadId"] = items[0].get("id")
                elif len(candidates) < 5:
                    candidates.append(entry)
            elif entry["grade"] != "HOLD":
                entry["nonConvertible"] = True
                entry["reasons"].append("待核实：缺少可追溯的有效证据或企业档案，不能创建线索")
            _emit(writer, "score", entry)
    finally:
        client.close()
    return {"candidates": candidates}


def generate_report(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """依据评分结果生成中文招商推荐报告。"""
    _node_start(writer, "generate_report")
    llm = make_llm()
    top = state["scored"][:8]
    text = _ask(
        llm,
        "你是招商推荐报告撰写器。依据给出的评分与理由撰写中文报告：先结论(优先跟进/待核实/暂缓名单),"
        "再逐企业说明推荐理由与待核实项,最后给出初次沟通话术要点。不得编造企业需求、联系人或房源信息;"
        "注册地/招聘/招投标不等于搬迁扩租需求,无证据必须写办公需求待核实。"
        "若来源为外部企业：说明已在企业资源库匹配到档案、可直接转线索的企业，以及未入库企业需先在企业资源库建档再跟进。",
        json.dumps({"building": state.get("building"), "requirements": state["requirements"],
                    "source": state.get("source"), "scores": top}, ensure_ascii=False),
    )
    _emit(writer, "report", {"text": text})
    return {"report": text}


def route_review(state: InvestmentAgentState) -> str:
    """有可跟进候选则进入人工审核，否则结束。"""
    return "human_review" if state.get("candidates") else END


def human_review(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """暂停图执行，等待人工审核通过或驳回。"""
    _node_start(writer, "human_review")
    payload = {
        "candidates": [
            {"enterpriseId": c["enterpriseId"], "enterpriseName": c["enterpriseName"],
             "totalScore": c["totalScore"], "grade": c["grade"]}
            for c in state["candidates"]
        ],
    }
    _emit(writer, "review_required", payload)
    decision = interrupt(payload)
    return {"approved": bool(decision.get("approved"))}


def route_decision(state: InvestmentAgentState) -> str:
    """审核通过建线索；事件模式驳回写观察结果，对话模式驳回则结束。"""
    if state.get("approved"):
        return "create_leads"
    return "save_observation" if state.get("mode") == "EVENT" else END


def create_leads(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """为已审核通过的候选企业创建待跟进线索。"""
    _node_start(writer, "create_leads")
    client = _client(writer)
    lead_ids = []
    try:
        for candidate in state["candidates"]:
            lead = client.create_pending_lead("create_leads", {
                "runId": state["run_id"],
                "enterpriseId": candidate["enterpriseId"],
                "projectId": state["project_id"],
                "signalIds": candidate["evidenceSignalIds"],
            })
            lead_ids.append(lead["id"])
            _emit(writer, "lead_created", {"leadId": lead["id"], "enterpriseName": candidate["enterpriseName"]})
    finally:
        client.close()
    return {"lead_ids": lead_ids}


def route_entry(state: InvestmentAgentState) -> str:
    """入口分流：数据事件走事件链，否则走对话招商链。"""
    return "load_event" if state.get("mode") == "EVENT" else "parse_requirements"


def route_after_score(state: InvestmentAgentState) -> str:
    """评分后：事件模式判定线索条件，对话模式先查重。"""
    return "lead_condition" if state.get("mode") == "EVENT" else "deduplicate"


def load_event(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """载入外部数据事件对应的企业档案与信号。"""
    _node_start(writer, "load_event")
    client = _client(writer)
    try:
        enterprise_id = state["enterprise_id"]
        tree = client.regions() or []
        company = client.get_company_detail("load_event", enterprise_id)
        signals = client.search_company_signals("load_event", enterprise_id) or []
        _emit(writer, "message", {"text": f"收到数据事件：{state.get('record_type')} · 企业 {company.get('name')}（#{enterprise_id}）"})
        return {"enriched": [{"company": company, "signals": signals}], "region_tree": tree, "source": "INTERNAL"}
    finally:
        client.close()


def route_source(state: InvestmentAgentState) -> str:
    """按记录类型选择招聘扩张分析或办公求租分析。"""
    return "analyze_rental" if state.get("record_type") == "RENTAL" else "analyze_recruitment"


def _analyze_event(state: InvestmentAgentState, writer: StreamWriter, system: str) -> dict:
    """事件链共用：将企业及信号摘要交给 LLM 做结构化分析。"""
    llm = make_llm()
    brief = [
        {
            "id": item["company"].get("id"),
            "name": item["company"].get("name"),
            "industry": item["company"].get("industryName"),
            "status": item["company"].get("businessStatus"),
            "signals": [
                {"type": s.get("signalType"), "title": s.get("title"), "source": s.get("sourceName"),
                 "observedAt": str(s.get("observedAt") or ""), "expiresAt": str(s.get("expiresAt") or ""),
                 "validity": s.get("validityStatus"), "demo": bool(s.get("isDemo")), "strength": s.get("strength")}
                for s in item["signals"]
            ],
        }
        for item in state["enriched"]
    ]
    text = _ask(llm, system, json.dumps(brief, ensure_ascii=False))
    analyses = parse_json(text, {})
    return {"analyses": analyses if isinstance(analyses, dict) else {}}


def analyze_recruitment(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """分析招聘类事件是否体现扩张证据及待核实点。"""
    _node_start(writer, "analyze_recruitment")
    return _analyze_event(
        state, writer,
        "你是企业招聘扩张分析器。必须以输入中实际记录的来源为准，演示、过期或无日期记录不能称为真实当前信号，招聘扩张不等于租赁意向。"
        "只输出 JSON 对象：键为企业 id 字符串，值为 {expansion_evidence: 一句话招聘扩张证据摘要(无信号写空字符串), "
        "needs_verification: 待核实问题数组}。不得编造信号与需求。",
    )


def analyze_rental(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """分析求租类事件的办公需求证据及待核实点。"""
    _node_start(writer, "analyze_rental")
    return _analyze_event(
        state, writer,
        "你是企业办公求租分析器。必须以输入中实际记录的来源为准，演示、过期或无日期记录不能称为当前真实需求，求租信号仍须核实时效、面积、预算与真实性。"
        "只输出 JSON 对象：键为企业 id 字符串，值为 {expansion_evidence: 一句话求租需求证据摘要(无信号写空字符串), "
        "needs_verification: 待核实问题数组}。不得编造面积、预算与联系方式。",
    )


def verify_enterprise(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """事件单企业主体核验：经营状态与企查查授权结果。"""
    _node_start(writer, "verify_enterprise")
    company = state["enriched"][0]["company"]
    status_text = str(company.get("businessStatus") or "")
    source_status = str(company.get("verificationStatus") or "PENDING").upper()
    revoked = any(keyword in status_text for keyword in ("注销", "吊销", "停业", "清算", "迁出"))
    if source_status in {"INACTIVE", "NOT_FOUND", "MISMATCH"}:
        verification = "REJECTED"
        note = str(company.get("verificationReason") or "企查查授权核验结果异常，需人工核实")
    elif revoked:
        verification = "REJECTED"
        note = f"经营状态「{status_text}」，主体核验不通过"
    elif source_status == "VERIFIED":
        verification = "VERIFIED"
        note = "已有有效的企查查授权核验结果，仍需关注后续变化"
    elif company.get("uscc"):
        verification = "PENDING"
        note = "档案中有统一社会信用代码，但外部工商核验未接入，主体仍待核验"
    else:
        verification = "PENDING"
        note = "缺少统一社会信用代码，主体待核验"
    _emit(writer, "message", {"text": f"企业核验：{note}"})
    return {"verification": verification}


def route_verify(state: InvestmentAgentState) -> str:
    """核验不通过写观察结果，否则继续实体关联与楼宇匹配。"""
    return "save_observation" if state.get("verification") == "REJECTED" else "associate_entity"


def associate_entity(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """确认事件企业已与资源库档案关联。"""
    _node_start(writer, "associate_entity")
    company = state["enriched"][0]["company"]
    _emit(writer, "message", {"text": f"企业实体关联：已关联企业资源库档案 #{company.get('id')} {company.get('name')}"})
    return {}


def _region_name(tree: list, code) -> str:
    """在地区树中按 code 解析地区中文名。"""
    for item in tree or []:
        if item.get("code") == code:
            return item.get("name") or ""
    return ""


def _num(value) -> float:
    """将配置或档案中的数值安全转为 float，无效则为 0。"""
    try:
        return float(value) if value not in (None, "") else 0
    except (TypeError, ValueError):
        return 0


def _pick_project(projects: list, company: dict):
    """按企业所在城市、省、行业与招商项目的契合度挑选最匹配项目。"""
    if not projects:
        return None
    province = str(company.get("provinceCode") or "")[:2]
    city = company.get("cityCode")
    industry = company.get("industryCode")

    def fitness(project):
        score = 0
        if city and project.get("cityCode") == city:
            score += 3
        elif province and str(project.get("provinceCode") or "")[:2] == province:
            score += 1
        if industry and project.get("industryCode") == industry:
            score += 2
        return score

    return max(projects, key=fitness)


def match_building(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """为事件企业匹配 ACTIVE 招商项目并回填楼宇面积等需求字段。"""
    _node_start(writer, "match_building")
    client = _client(writer)
    try:
        projects = client.list_projects("match_building")
        company = state["enriched"][0]["company"]
        best = _pick_project(projects, company)
        if not best:
            _emit(writer, "message", {"text": "楼宇匹配：暂无招商中的项目（ACTIVE），生成观察结果"})
            return {"project_id": None, "building": None}
        building = client.get_building_info("match_building", best["id"])
        requirements = {
            "city": _region_name(state.get("region_tree") or [], best.get("cityCode")),
            "district": "",
            "industry_keywords": [],
            "area_min": _num(building.get("areaMin")),
            "area_max": _num(building.get("areaMax")),
        }
        _emit(writer, "message", {"text": f"楼宇匹配：选中「{best.get('projectName')}」（#{best['id']}）"})
        return {"project_id": best["id"], "building": building, "requirements": requirements}
    finally:
        client.close()


def route_building(state: InvestmentAgentState) -> str:
    """匹配到项目则评分，否则仅生成观察说明。"""
    return "calculate_score" if state.get("project_id") else "save_observation"


def lead_condition(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """事件模式：查重后筛出满足建线索条件的候选（不限 5 家）。"""
    _node_start(writer, "lead_condition")
    client = _client(writer)
    candidates = []
    try:
        for entry in state["scored"]:
            entry["projectId"] = state["project_id"]
            entry["duplicateLeadId"] = None
            if entry["grade"] != "HOLD" and entry.get("enterpriseId") and entry.get("evidenceSignalIds"):
                existing = client.check_duplicate_lead("lead_condition", entry["enterpriseId"], state["project_id"])
                items = existing.get("items") or []
                if items:
                    entry["duplicateLeadId"] = items[0].get("id")
                else:
                    candidates.append(entry)
            _emit(writer, "score", entry)
    finally:
        client.close()
    return {"candidates": candidates}


def route_event_review(state: InvestmentAgentState) -> str:
    """事件链：有候选则出报告进人工审核，否则写观察结果。"""
    return "generate_report" if state.get("candidates") else "save_observation"


def save_observation(state: InvestmentAgentState, writer: StreamWriter) -> dict:
    """未建线索时汇总原因并推送观察/报告说明（本次不写库）。"""
    _node_start(writer, "save_observation")
    entries = state.get("scored") or []
    entry = entries[0] if entries else {}
    company = (state.get("enriched") or [{}])[0].get("company", {})
    if state.get("verification") == "REJECTED":
        status, base = "VERIFY_FAILED", "企业核验未通过"
    elif state.get("approved") is False:
        status, base = "REJECTED", "人工审核驳回，暂不创建线索"
    elif not state.get("project_id"):
        status, base = "SUB_THRESHOLD", "暂无匹配的招商项目/楼宇"
    elif entry.get("duplicateLeadId"):
        status, base = "SUB_THRESHOLD", f"已有线索 #{entry['duplicateLeadId']}，不重复创建"
    else:
        status, base = "SUB_THRESHOLD", "未满足线索创建条件，暂缓跟进"
    detail = "；".join((entry.get("reasons") or [])[:3])
    reason = f"{base}。{detail}" if detail else base
    _emit(writer, "observation", {"enterpriseName": company.get("name"), "status": status,
                                  "reason": reason, "saved": False})
    report = f"观察结果：{company.get('name') or '未知企业'}。{reason}。本次未自动写入线索或观察记录。"
    _emit(writer, "report", {"text": report})
    return {"observation_id": None, "report": report}


def build_graph(checkpointer):
    """注册全部节点与条件边，编译为可 checkpoint 的 LangGraph。"""
    graph = StateGraph(InvestmentAgentState)
    graph.add_node("parse_requirements", parse_requirements)
    graph.add_node("validate_requirements", validate_requirements)
    graph.add_node("search_companies", search_companies)
    graph.add_node("enrich_company", enrich_company)
    graph.add_node("verify_candidates", verify_candidates)
    graph.add_node("associate_candidates", associate_candidates)
    graph.add_node("analyze_signals", analyze_signals)
    graph.add_node("calculate_score", calculate_score)
    graph.add_node("deduplicate", deduplicate)
    graph.add_node("generate_report", generate_report)
    graph.add_node("human_review", human_review)
    graph.add_node("create_leads", create_leads)
    graph.add_node("load_event", load_event)
    graph.add_node("analyze_recruitment", analyze_recruitment)
    graph.add_node("analyze_rental", analyze_rental)
    graph.add_node("verify_enterprise", verify_enterprise)
    graph.add_node("associate_entity", associate_entity)
    graph.add_node("match_building", match_building)
    graph.add_node("lead_condition", lead_condition)
    graph.add_node("save_observation", save_observation)
    graph.add_conditional_edges(START, route_entry)
    graph.add_edge("parse_requirements", "validate_requirements")
    graph.add_conditional_edges("validate_requirements", route_validate)
    graph.add_conditional_edges("search_companies", route_search)
    graph.add_edge("enrich_company", "verify_candidates")
    graph.add_edge("verify_candidates", "associate_candidates")
    graph.add_edge("associate_candidates", "analyze_signals")
    graph.add_edge("analyze_signals", "calculate_score")
    graph.add_conditional_edges("calculate_score", route_after_score)
    graph.add_edge("deduplicate", "generate_report")
    graph.add_conditional_edges("generate_report", route_review)
    graph.add_conditional_edges("load_event", route_source)
    graph.add_edge("analyze_recruitment", "verify_enterprise")
    graph.add_edge("analyze_rental", "verify_enterprise")
    graph.add_conditional_edges("verify_enterprise", route_verify)
    graph.add_edge("associate_entity", "match_building")
    graph.add_conditional_edges("match_building", route_building)
    graph.add_conditional_edges("lead_condition", route_event_review)
    graph.add_conditional_edges("human_review", route_decision)
    graph.add_edge("create_leads", END)
    graph.add_edge("save_observation", END)
    return graph.compile(checkpointer=checkpointer)
