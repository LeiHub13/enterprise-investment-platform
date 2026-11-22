"""Entity association must not silently merge different legal subjects."""
import pytest

from app.entity_matching import resolve_entity


HEAD_OFFICE = {"id": 1, "name": "南京示例科技有限公司", "uscc": "913200000000000001",
               "website": "https://example.test", "registeredAddress": "南京市示例路1号"}
BRANCH = {"id": 2, "name": "南京示例科技有限公司（鼓楼分公司）", "uscc": "913200000000000002",
          "website": "https://example.test", "registeredAddress": "南京市示例路1号"}


def test_exact_credit_code_resolves_to_its_own_subject():
    result = resolve_entity({"name": BRANCH["name"], "uscc": BRANCH["uscc"]}, [HEAD_OFFICE, BRANCH])
    assert result["status"] == "MATCH" and result["match"]["id"] == 2


@pytest.mark.parametrize("facts,candidates", [
    ({"name": HEAD_OFFICE["name"], "uscc": BRANCH["uscc"]}, [HEAD_OFFICE]),
    ({"name": HEAD_OFFICE["name"], "uscc": "invalid"}, [HEAD_OFFICE]),
    ({"name": HEAD_OFFICE["name"]}, [HEAD_OFFICE, {**HEAD_OFFICE, "id": 3}]),
    ({"name": BRANCH["name"], "website": BRANCH["website"], "registeredAddress": BRANCH["registeredAddress"]}, [HEAD_OFFICE]),
    ({"name": BRANCH["name"], "website": BRANCH["website"]}, [HEAD_OFFICE]),
    ({"name": "未知企业", "website": "https://[invalid"}, [HEAD_OFFICE]),
])
def test_conflicting_or_insufficient_identity_evidence_is_pending(facts, candidates):
    result = resolve_entity(facts, candidates)
    assert result["status"] == "PENDING" and result["match"] is None


def test_full_legal_name_may_match_but_is_not_official_verification():
    result = resolve_entity({"name": "南京示例科技有限公司(鼓楼分公司)"}, [HEAD_OFFICE, BRANCH])
    assert result["status"] == "MATCH" and result["match"]["id"] == 2
    assert "仍需工商核验" in result["reason"]


def test_incomplete_results_cannot_establish_a_unique_match():
    assert resolve_entity({"name": HEAD_OFFICE["name"]}, [HEAD_OFFICE], complete=False)["status"] == "PENDING"
    assert resolve_entity({"uscc": HEAD_OFFICE["uscc"]}, [HEAD_OFFICE], complete=False)["status"] == "PENDING"
