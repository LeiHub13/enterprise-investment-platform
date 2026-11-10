from datetime import date, timedelta

import pytest

from app.priority_scoring import score_company


TODAY = date(2026, 11, 9)
COMPANY = {"id": 12, "name": "测试企业", "uscc": "TEST-CODE", "businessStatus": "存续",
           "industryName": "软件", "cityCode": "320100", "districtCode": "320106"}
REGIONS = [{"name": "南京市", "level": "city", "code": "320100"},
           {"name": "鼓楼区", "level": "district", "code": "320106", "parentCode": "320100"}]
REQUIREMENTS = {"city": "南京", "district": "鼓楼", "industry_keywords": ["软件"]}
BUILDING = {"areaMin": 100, "areaMax": 300, "rentPrice": None}


def signal(kind="RENTAL", **updates):
    return {"signalType": kind, "sourceRecordId": 45, "sourceName": "MANUAL",
            "publishedAt": TODAY.isoformat(), "validityStatus": "VALID", **updates}


def score(signals, company=None, building=None):
    return score_company(company or COMPANY, signals, {}, REQUIREMENTS, REGIONS, building or BUILDING, today=TODAY)


@pytest.mark.parametrize("changes", [
    {"isDemo": True},
    {"validityStatus": "EXPIRED"},
    {"validityStatus": None},
    {"publishedAt": None},
    {"publishedAt": (TODAY - timedelta(days=31)).isoformat()},
    {"publishedAt": (TODAY + timedelta(days=1)).isoformat()},
    {"expiresAt": (TODAY - timedelta(days=1)).isoformat()},
    {"sourceRecordId": None},
])
def test_invalid_rentals_do_not_establish_present_intent(changes):
    result = score([signal(**changes)])
    assert result["intentScore"] == 0
    assert any("未知租赁意向" in reason for reason in result["reasons"])


def test_recruitment_is_indirect_and_less_than_rental_intent():
    recruitment = score([signal("RECRUITMENT")])
    rental = score([signal("RENTAL")])
    assert 0 < recruitment["intentScore"] <= 20 < rental["intentScore"]
    assert any("招聘不等于租赁需求" in reason for reason in recruitment["reasons"])


def test_unknown_rent_does_not_penalize_budget():
    low = score([], company={**COMPANY, "budgetPerSqmDay": 1})
    high = score([], company={**COMPANY, "budgetPerSqmDay": 100})
    assert low["fitScore"] == high["fitScore"]
    assert any("预算不参与" in reason for reason in low["reasons"])


def test_priority_is_the_configured_weighted_sum():
    result = score([signal()])
    assert result["totalScore"] == result["priorityScore"]
    assert result["priorityScore"] == round(result["fitScore"] * 0.45 + result["intentScore"] * 0.40
                                            + result["confidenceScore"] * 0.15)
    assert all(0 <= result[key] <= 100 for key in ("fitScore", "intentScore", "confidenceScore", "priorityScore"))


def test_weights_must_be_complete_and_valid(monkeypatch):
    monkeypatch.setenv("AGENT_SCORE_WEIGHTS", '{"priority":{"fit":0.5,"intent":0.5}}')
    with pytest.raises(ValueError):
        score([])


def test_source_names_alone_do_not_prove_independence():
    one = score([signal("RECRUITMENT", sourceName="QCC")])
    two = score([signal("RECRUITMENT", sourceName="QCC"), signal("RECRUITMENT", sourceName="58")])
    assert one["confidenceScore"] == two["confidenceScore"]
    assert any("独立来源" in reason for reason in two["reasons"])


def test_verified_source_improves_confidence_but_mismatch_blocks_lead():
    verified = score([signal()], company={**COMPANY, "verificationStatus": "VERIFIED", "verificationAt": "2026-11-09"})
    pending = score([signal()], company=COMPANY)
    mismatch = score([signal()], company={**COMPANY, "verificationStatus": "MISMATCH"})
    assert verified["confidenceScore"] > pending["confidenceScore"]
    assert any("企查查授权" in reason for reason in verified["reasons"])
    assert mismatch["grade"] == "HOLD" and any("主体核验存在异常" in reason for reason in mismatch["reasons"])


def test_inactive_archived_company_is_held_even_without_verification():
    result = score([signal()], company={**COMPANY, "businessStatus": "注销"})
    assert result["grade"] == "HOLD"
