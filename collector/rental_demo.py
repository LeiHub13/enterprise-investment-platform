"""Explicit opt-in 58 mock data; it never calls the real 58 platform."""
import argparse
import hashlib
from datetime import datetime, timedelta

from . import config, uploader
from .connectors.fifty_eight import MockRentalConnector, RentalQuery, RentalRecord


def main():
    parser = argparse.ArgumentParser(description="生成明确标记为演示的 58 模拟求租记录")
    parser.add_argument("--company", required=True, help="企业资源库中已存在的企业全称")
    parser.add_argument("--uscc", default="", help="统一社会信用代码；存在同名企业时必须提供")
    parser.add_argument("--area-min", type=float, default=100)
    parser.add_argument("--area-max", type=float, default=300)
    parser.add_argument("--upload", action="store_true", help="明确同意将演示记录写入已配置的业务服务")
    args = parser.parse_args()
    now = datetime.now().astimezone()
    identity = hashlib.sha256((args.uscc or args.company).encode("utf-8")).hexdigest()[:24]
    record = RentalRecord(
        external_id=f"DEMO:{identity}:{now.date()}:{args.area_min:g}:{args.area_max:g}",
        company_name=args.company, city="南京", district="鼓楼区", area_min=args.area_min, area_max=args.area_max,
        budget=None, published_at=now, updated_at=now, expires_at=now + timedelta(days=7), intermediary=None,
    )
    try:
        records = MockRentalConnector([record], now=now).fetch(RentalQuery(city="南京")).records
        if not args.upload:
            print(f"【演示预览】{args.company}：南京鼓楼区 {args.area_min:g}～{args.area_max:g}㎡，未调用 58，也未写入数据库。")
            return 0
        if not config.COLLECTOR_TOKEN:
            raise uploader.UploadError("未配置 COLLECTOR_TOKEN。")
        company = uploader.resolve_company(args.company, args.uscc)
        if not company:
            raise uploader.UploadError("企业资源库中未找到该企业。")
        result = uploader.upload_rental_demo(company["id"], records[0])
        print(f"【演示】求租记录 {'已创建' if result.get('created') else '已存在'}，记录 ID：{result.get('recordId')}。")
        return 0
    except (ValueError, uploader.UploadError) as error:
        print(f"演示记录处理失败：{error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
