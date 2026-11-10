"""Opt-in RabbitMQ consumer: durable retries and one authorized Agent run per event.

The API token belongs to the user who will review resulting tasks. It is read
from the environment and never written into RabbitMQ messages or logs.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
import time
import urllib.error
import urllib.request

import pika

from collector import config
from collector.events.publisher import DEFAULT_QUEUE

MAX_RESPONSE_BYTES = 4_000_000
DEFAULT_RETRY_SECONDS = 10
DEFAULT_MAX_ATTEMPTS = 90


class ConsumerError(RuntimeError):
    pass


def _with_heartbeat(connection, fn, *args):
    # A model run may take minutes. Keep the broker connection alive while the
    # HTTP request waits, so its unacknowledged message is not redelivered early.
    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(fn, *args)
        while not future.done():
            connection.process_data_events(time_limit=1)
        return future.result()


def _dispatch(base_url: str, token: str, event_id: int, record_id: int) -> dict:
    payload = json.dumps({"eventId": event_id, "recordId": record_id}).encode("utf-8")
    request = urllib.request.Request(base_url.rstrip("/") + "/api/agent/events/dispatch", data=payload,
        headers={"Content-Type": "application/json", "Accept": "text/event-stream, application/json",
                 "Authorization": "Bearer " + token}, method="POST")
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=630) as response:
            body = response.read(MAX_RESPONSE_BYTES + 1)
            if len(body) > MAX_RESPONSE_BYTES:
                return {"action": "RETRY", "reason": "响应过长，稍后检查任务状态"}
            content_type = response.headers.get("Content-Type", "")
            if "application/json" in content_type:
                data = json.loads(body.decode("utf-8"))
                if not isinstance(data, dict):
                    return {"action": "RETRY", "reason": "业务服务返回了无法识别的响应"}
                status = data.get("status")
                if status in {"DONE", "AWAITING_REVIEW", "APPROVING", "REJECTING", "REJECTED", "SKIPPED"}:
                    return {"action": "ACK", "status": status, "runId": data.get("runId")}
                if status == "ERROR":
                    return {"action": "FAILED", "reason": "Agent 任务已失败", "runId": data.get("runId")}
                return {"action": "RETRY", "reason": "Agent 任务仍在运行", "runId": data.get("runId")}
            if "text/event-stream" not in content_type:
                return {"action": "RETRY", "reason": "业务服务返回了无法识别的响应"}
            current, run_id, final_status, failed = "", None, None, False
            for line in body.decode("utf-8").splitlines():
                if line.startswith("event:"):
                    current = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:].strip())
                    if not isinstance(data, dict):
                        return {"action": "RETRY", "reason": "Agent 响应格式异常"}
                    if current == "meta":
                        run_id = data.get("runId")
                    elif current == "done":
                        final_status = data.get("status")
                    elif current == "error":
                        failed = True
                    current = ""
            if failed:
                return {"action": "FAILED", "reason": "Agent 任务已失败，请检查运行记录", "runId": run_id}
            if run_id and final_status in {"DONE", "AWAITING_REVIEW", "REJECTED"}:
                return {"action": "ACK", "status": final_status, "runId": run_id}
            return {"action": "RETRY", "reason": "Agent 响应中断，稍后检查任务状态", "runId": run_id}
    except urllib.error.HTTPError as error:
        if error.code in {400, 404, 409, 422}:
            return {"action": "FAILED", "reason": f"业务服务拒绝事件（HTTP {error.code}）"}
        return {"action": "RETRY", "reason": f"业务服务暂不可用或授权失效（HTTP {error.code}）"}
    except (urllib.error.URLError, OSError, TimeoutError, json.JSONDecodeError, UnicodeDecodeError):
        return {"action": "RETRY", "reason": "业务服务连接或响应异常"}


def consume_one(broker_url: str, base_url: str, token: str, *, queue: str = DEFAULT_QUEUE,
                retry_seconds: int = DEFAULT_RETRY_SECONDS, max_attempts: int = DEFAULT_MAX_ATTEMPTS) -> dict:
    if not broker_url or not base_url or not token:
        raise ConsumerError("请配置 RABBITMQ_URL、BUSINESS_BASE_URL 和 AGENT_EVENT_API_TOKEN。")
    if not queue or len(queue.encode("utf-8")) > 240 or retry_seconds < 1 or max_attempts < 1:
        raise ConsumerError("队列名称或重试配置无效。")
    try:
        connection = pika.BlockingConnection(pika.URLParameters(broker_url))
    except Exception:
        raise ConsumerError("无法连接 RabbitMQ，请检查服务和连接配置。") from None
    try:
        channel = connection.channel()
        retry_queue, failed_queue = queue + ".retry", queue + ".failed"
        for name in (queue, retry_queue, failed_queue):
            channel.queue_declare(queue=name, durable=True)
        channel.confirm_delivery()

        method, properties, body = channel.basic_get(queue=retry_queue, auto_ack=False)
        if method is not None and int((properties.headers or {}).get("x-next-at", 0)) > int(time.time()):
            channel.basic_nack(method.delivery_tag, requeue=True)
            method = None
        if method is None:
            method, properties, body = channel.basic_get(queue=queue, auto_ack=False)
        if method is None:
            return {"action": "EMPTY"}

        headers = dict(properties.headers or {})
        try:
            if len(body) > 1_000_000:
                raise ValueError("event too large")
            event = json.loads(body)
            event_id, record_id = event.get("id"), event.get("sourceRecordId")
            if (not isinstance(event, dict) or not isinstance(event_id, int) or isinstance(event_id, bool) or event_id <= 0
                    or not isinstance(record_id, int) or isinstance(record_id, bool) or record_id <= 0
                    or properties.message_id != str(event_id)):
                raise ValueError("invalid event")
            result = _with_heartbeat(connection, _dispatch, base_url, token, event_id, record_id)
        except (ValueError, TypeError, AttributeError, json.JSONDecodeError, UnicodeDecodeError):
            result = {"action": "FAILED", "reason": "消息格式或事件标识无效"}

        action = result["action"]
        if action == "ACK":
            channel.basic_ack(method.delivery_tag)
            return result
        attempts = int(headers.get("x-attempt", 0)) + 1
        if action == "RETRY" and attempts < max_attempts:
            target = retry_queue
            headers.update({"x-attempt": attempts, "x-next-at": int(time.time()) + retry_seconds})
        else:
            target = failed_queue
            headers.update({"x-attempt": attempts, "x-failure-reason": result.get("reason", "处理失败")})
            action = "FAILED"
        try:
            channel.basic_publish(exchange="", routing_key=target, body=body, mandatory=True,
                properties=pika.BasicProperties(content_type="application/json", delivery_mode=2,
                    message_id=properties.message_id, headers=headers))
            channel.basic_ack(method.delivery_tag)
        except Exception:
            channel.basic_nack(method.delivery_tag, requeue=True)
            raise ConsumerError("无法确认重试或失败消息投递，原消息保留待重试。") from None
        return {**result, "action": action, "attempt": attempts}
    except ConsumerError:
        raise
    except Exception:
        raise ConsumerError("事件消费失败，请检查 RabbitMQ 配置。") from None
    finally:
        if connection.is_open:
            connection.close()


def main():
    parser = argparse.ArgumentParser(description="消费采集事件并以授权用户身份启动招商 Agent")
    parser.add_argument("--watch", action="store_true", help="持续消费；默认仅处理一条")
    parser.add_argument("--queue", default=DEFAULT_QUEUE)
    parser.add_argument("--retry-seconds", type=int, default=DEFAULT_RETRY_SECONDS, help="失败后等待秒数，至少 1 秒")
    parser.add_argument("--max-attempts", type=int, default=DEFAULT_MAX_ATTEMPTS, help="最多尝试次数")
    args = parser.parse_args()
    while True:
        try:
            result = consume_one(os.environ.get("RABBITMQ_URL", ""), config.BUSINESS_BASE_URL,
                                 os.environ.get("AGENT_EVENT_API_TOKEN", ""), queue=args.queue,
                                 retry_seconds=args.retry_seconds, max_attempts=args.max_attempts)
            if result["action"] != "EMPTY":
                print(f"事件处理：{result['action']}，任务：{result.get('runId') or '无'}，{result.get('reason') or result.get('status') or ''}")
        except ConsumerError as error:
            print(f"事件消费失败：{error}")
            if not args.watch:
                return 1
        if not args.watch:
            return 0
        time.sleep(2)


if __name__ == "__main__":
    raise SystemExit(main())
