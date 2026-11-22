"""Explicit opt-in QCC enterprise verification; preview is demo-only."""
import argparse

from . import config, qcc, qcc_verification, uploader


def main():
    parser = argparse.ArgumentParser(description="企业主体核验（默认只预览演示数据）")
    parser.add_argument("--company", required=True, help="企业资源库中的完整企业名称")
    parser.add_argument("--uscc", default="", help="统一社会信用代码；同名企业必须提供")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--execute", action="store_true", help="调用已授权的企查查 2001 接口并保存结果，可能计费")
    group.add_argument("--upload-demo", action="store_true", help="将明确标记的演示核验记录写入业务系统")
    args = parser.parse_args()
    try:
        if not args.execute and not args.upload_demo:
            qcc_verification.verify_company(args.company, args.uscc, dry_run=True)
            print("【演示预览】未调用企查查、未写入数据库；演示结果不代表真实核验。")
            return 0
        if not config.COLLECTOR_TOKEN:
            raise uploader.UploadError("未配置 COLLECTOR_TOKEN。")
        company = uploader.resolve_company(args.company, args.uscc)
        if not company:
            raise uploader.UploadError("企业资源库中未找到该企业。")
        result = qcc_verification.verify_company(company["name"], company.get("uscc") or args.uscc,
                                                 dry_run=args.upload_demo)
        stored = uploader.upload_verification(company["id"], result)
        print(f"{'【演示】' if args.upload_demo else '企查查授权核验'}：{stored.get('resultStatus')}；{stored.get('reason')}。")
        return 0
    except (qcc.QccError, uploader.UploadError) as error:
        print(f"核验失败：{error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
