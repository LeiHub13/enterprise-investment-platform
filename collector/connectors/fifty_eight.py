"""58 adapter boundary. No real endpoint is implemented without a signed API contract."""
from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime, timezone
import math


@dataclass(frozen=True)
class RentalQuery:
    city: str
    district: str | None = None
    changed_since: datetime | None = None
    cursor: str | None = None
    limit: int = 50


@dataclass(frozen=True)
class RentalRecord:
    external_id: str
    company_name: str | None
    city: str
    district: str | None
    area_min: float | None
    area_max: float | None
    budget: float | None
    published_at: datetime
    updated_at: datetime
    expires_at: datetime | None
    intermediary: bool | None
    demo: bool = True


@dataclass(frozen=True)
class RentalPage:
    records: list[RentalRecord]
    next_cursor: str | None
    demo: bool


class RentalConnector(ABC):
    @abstractmethod
    def fetch(self, query: RentalQuery) -> RentalPage:
        """Fetch one page; implementations preserve source, dates and authorization status."""


class FiftyEightRentalConnector(RentalConnector):
    def fetch(self, query: RentalQuery) -> RentalPage:
        raise NotImplementedError("58 合作接口尚未授权，需先提供正式接口文档与凭证。")


class MockRentalConnector(RentalConnector):
    """Explicit demonstration records only; an empty list is the safe default."""

    def __init__(self, records: list[RentalRecord] | None = None, *, now: datetime | None = None):
        self._records = list(records or [])
        if any(record.demo is not True for record in self._records):
            raise ValueError("Mock 求租记录必须标记为演示数据")
        self._now = now or datetime.now(timezone.utc)
        if self._now.utcoffset() is None or any(
            r.published_at.utcoffset() is None or r.updated_at.utcoffset() is None
            or (r.expires_at is not None and r.expires_at.utcoffset() is None)
            for r in self._records
        ):
            raise ValueError("演示记录时间必须带时区")
        if any((r.area_min is not None and (not math.isfinite(r.area_min) or r.area_min <= 0))
               or (r.area_max is not None and (not math.isfinite(r.area_max) or r.area_max <= 0))
               or (r.budget is not None and (not math.isfinite(r.budget) or r.budget < 0))
               or (r.area_min is not None and r.area_max is not None and r.area_min > r.area_max)
               for r in self._records):
            raise ValueError("演示记录面积范围无效")

    def fetch(self, query: RentalQuery) -> RentalPage:
        if not query.city.strip() or not 1 <= query.limit <= 100:
            raise ValueError("城市不能为空，分页数量应为 1～100")
        if query.changed_since is not None and query.changed_since.utcoffset() is None:
            raise ValueError("增量同步时间必须带时区")
        if query.cursor is not None and (not query.cursor.isdecimal() or len(query.cursor) > 9):
            raise ValueError("无效的演示分页游标")
        offset = int(query.cursor or "0")
        records = [r for r in self._records if r.city == query.city
                   and (not query.district or r.district == query.district)
                   and (query.changed_since is None or r.updated_at >= query.changed_since)
                   and (r.expires_at is None or r.expires_at >= self._now)
                   and r.published_at <= self._now]
        records.sort(key=lambda r: (r.updated_at, r.external_id))
        page = records[offset:offset + query.limit]
        next_offset = offset + len(page)
        return RentalPage(records=page, next_cursor=str(next_offset) if next_offset < len(records) else None, demo=True)
