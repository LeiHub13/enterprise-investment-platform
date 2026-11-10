"""Publish transactional outbox events with RabbitMQ publisher confirms."""
import argparse
import json
import os
import time

import pika

from collector import config, uploader

DEFAULT_QUEUE = "investment.collector.records"


class PublishError(RuntimeError):
    pass


def publish_pending(broker_url: str, *, queue: str = DEFAULT_QUEUE, batch_size: int = 20) -> int:
    if not broker_url or not config.COLLECTOR_TOKEN:
        raise PublishError("请配置 RABBITMQ_URL 和 COLLECTOR_TOKEN。")
    if not queue or not 1 <= batch_size <= 100:
        raise PublishError("队列名称或批量大小无效。")
    try:
        connection = pika.BlockingConnection(pika.URLParameters(broker_url))
    except Exception:
        raise PublishError("无法连接 RabbitMQ，请检查服务和连接配置。") from None
    try:
        channel = connection.channel()
        channel.queue_declare(queue=queue, durable=True)
        channel.confirm_delivery()
        events = uploader.claim_outbox(batch_size)
        sent = 0
        for event in events:
            event_id, lease = int(event["id"]), event["leaseToken"]
            payload = {key: event.get(key) for key in
                       ("id", "eventType", "sourceRecordId", "enterpriseId", "source", "recordType",
                        "publishedAt", "fetchedAt", "isDemo")}
            payload.update({key: event.get(key) for key in ("validityStatus", "recordVersion", "changeType")})
            try:
                channel.basic_publish(exchange="", routing_key=queue,
                    body=json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8"),
                    properties=pika.BasicProperties(content_type="application/json", delivery_mode=2,
                                                    message_id=str(event_id)), mandatory=True)
            except Exception:
                try:
                    uploader.retry_outbox(event_id, lease)
                except uploader.UploadError:
                    pass  # Lease expiry permits recovery if the API is unavailable.
                raise PublishError("RabbitMQ 未确认事件，已安排重试或等待租约到期。") from None
            # If acknowledgment fails after broker confirmation, the lease expires and
            # the event can be delivered again with the same message_id.
            uploader.ack_outbox(event_id, lease)
            sent += 1
        return sent
    except uploader.UploadError:
        raise PublishError("业务服务无法确认事件状态，请稍后重试。") from None
    except PublishError:
        raise
    except Exception:
        raise PublishError("事件投递失败，请检查 RabbitMQ 和业务服务。") from None
    finally:
        if connection.is_open:
            connection.close()


def main():
    parser = argparse.ArgumentParser(description="采集事件可靠投递")
    parser.add_argument("--watch", action="store_true", help="持续轮询待发送事件")
    parser.add_argument("--interval", type=int, default=10, help="轮询间隔秒数，最少 5 秒")
    args = parser.parse_args()
    while True:
        try:
            sent = publish_pending(os.environ.get("RABBITMQ_URL", ""))
            print(f"已确认投递 {sent} 条采集事件。")
        except PublishError as error:
            print(f"投递失败：{error}")
            if not args.watch:
                return 1
        if not args.watch:
            return 0
        time.sleep(max(5, args.interval))


if __name__ == "__main__":
    raise SystemExit(main())
