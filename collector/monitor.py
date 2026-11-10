"""Run paused-by-default, per-company recruitment monitoring tasks.

Tasks are claimed via the business API; the worker does not access MySQL.
"""
import argparse
import time

from . import config, normalize, qcc, qcc_verification, uploader


def run_once() -> dict:
    tasks = uploader.claim_monitor(1)
    if not tasks:
        return {"status": "IDLE"}
    task = tasks[0]
    result = {"leaseToken": task["leaseToken"], "status": "FAILED", "errorCode": "UNKNOWN",
              "createdRecords": 0, "updatedRecords": 0, "pagesFetched": 0, "possiblyTruncated": False}
    mode = task.get("mode")
    verification_warning = None
    try:
        if mode not in {"DEMO", "LIVE"}:
            raise ValueError("unsupported monitor mode")
        if mode == "LIVE" and config.QCC_VERIFY_ON_MONITOR:
            try:
                latest = uploader.latest_verification(int(task["enterpriseId"]))
                fresh = str(latest.get("verificationFresh") or "").strip().lower() in {"1", "true", "yes"}
                if not fresh:
                    verification = qcc_verification.verify_company(task.get("enterpriseName") or "", task.get("uscc") or "")
                    uploader.upload_verification(int(task["enterpriseId"]), verification)
            except (qcc.QccError, uploader.UploadError, ValueError, KeyError, TypeError):
                # Verification is optional. Keep recruiting collection running, but log a fixed warning.
                verification_warning = "QCC_VERIFICATION_FAILED"
        try:
            data = qcc.fetch_recruitment_pages(task.get("enterpriseName") or "", task.get("uscc") or "",
                dry_run=mode == "DEMO", max_pages=int(task.get("maxPages") or 1))
        except qcc.QccError:
            result["errorCode"] = "QCC_CONFIG" if mode == "LIVE" and not (config.QCC_APP_KEY and config.QCC_SECRET_KEY) else "QCC_FAILURE"
            raise
        result["pagesFetched"] = data["pagesFetched"]
        result["possiblyTruncated"] = data["possiblyTruncated"]
        snapshot = normalize.normalize(task.get("enterpriseName") or "", task.get("uscc") or "", data["items"],
                                       dry_run=mode == "DEMO")
        if snapshot is None:
            result.update(status="EMPTY", errorCode=None)
        else:
            try:
                response = uploader.upload_recruitment(int(task["enterpriseId"]), snapshot, dry_run=mode == "DEMO")
            except uploader.UploadError:
                result["errorCode"] = "UPLOAD_FAILURE"
                raise
            if response.get("created"):
                result["updatedRecords" if response.get("changeType") == "UPDATED" else "createdRecords"] = 1
            result.update(status="SUCCESS", errorCode=None)
    except (qcc.QccError, uploader.UploadError, ValueError, KeyError, TypeError):
        pass  # Only fixed error codes are persisted; secrets and response bodies stay out of logs.
    except Exception:
        result["errorCode"] = "UNKNOWN"
    uploader.finish_monitor(int(task["id"]), result)
    return {"status": result["status"], "taskId": task["id"], "mode": mode,
            "createdRecords": result["createdRecords"], "updatedRecords": result["updatedRecords"],
            "errorCode": result["errorCode"], "verificationWarning": verification_warning}


def main():
    parser = argparse.ArgumentParser(description="领取并执行企业招聘监测任务")
    parser.add_argument("--watch", action="store_true", help="持续领取到期任务；默认仅执行一条")
    parser.add_argument("--poll-seconds", type=int, default=30, help="空闲时的轮询间隔，最少 5 秒")
    args = parser.parse_args()
    if not config.COLLECTOR_TOKEN:
        print("未配置 COLLECTOR_TOKEN，监测任务不能运行。")
        return 2
    while True:
        try:
            result = run_once()
            if result["status"] != "IDLE":
                print(f"监测任务 #{result['taskId']}：{result['status']}（{result['mode']}）"
                      f"；新增 {result['createdRecords']}，更新 {result['updatedRecords']}，错误 {result['errorCode'] or '无'}。")
                if result.get("verificationWarning"):
                    print("主体核验未成功，请检查 2001 接口授权、配置及业务服务。")
        except uploader.UploadError:
            print("无法领取或完成监测任务，请检查业务服务及采集令牌。")
            if not args.watch:
                return 1
            result = {"status": "IDLE"}
        if not args.watch:
            return 0
        if result.get("status") == "IDLE":
            time.sleep(max(5, args.poll_seconds))


if __name__ == "__main__":
    raise SystemExit(main())
