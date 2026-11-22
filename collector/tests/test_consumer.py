import json
import os
import time
import uuid

import pika
import pytest

from collector.events import consumer


BODY = json.dumps({"id": 31, "sourceRecordId": 44, "isDemo": False}).encode()


class FakeChannel:
    def __init__(self):
        self.pending = [(object(), pika.BasicProperties(message_id="31"), BODY)]
        self.acknowledged, self.rejected, self.published = [], [], []
        self.fail_publish = False

    def queue_declare(self, **kwargs): pass
    def confirm_delivery(self): pass
    def basic_get(self, queue, auto_ack):
        if queue.endswith(".retry") or not self.pending:
            return None, None, None
        _, properties, body = self.pending.pop(0)
        return type("Delivery", (), {"delivery_tag": 1})(), properties, body
    def basic_ack(self, tag): self.acknowledged.append(tag)
    def basic_nack(self, tag, requeue): self.rejected.append((tag, requeue))
    def basic_publish(self, **kwargs):
        if self.fail_publish: raise RuntimeError("publish error")
        self.published.append(kwargs)


class FakeConnection:
    is_open = True
    def __init__(self, channel): self._channel = channel
    def channel(self): return self._channel
    def process_data_events(self, time_limit): time.sleep(0.001)
    def close(self): self.is_open = False


@pytest.mark.parametrize(("content_type", "body", "action", "status"), [
    ("application/json", b'{"status":"AWAITING_REVIEW","runId":"r"}', "ACK", "AWAITING_REVIEW"),
    ("application/json", b'{"status":"RUNNING","runId":"r"}', "RETRY", None),
    ("text/event-stream", b'event: meta\ndata: {"runId":"r"}\n\nevent: done\ndata: {"status":"DONE"}\n\n', "ACK", "DONE"),
    ("text/event-stream", b'event: meta\ndata: {"runId":"r"}\n\nevent: error\ndata: {"message":"private detail"}\n\n', "FAILED", None),
    ("text/event-stream", b'event: done\ndata: {"status":"DONE"}\n\n', "RETRY", None),
])
def test_dispatch_interprets_existing_and_streamed_runs(monkeypatch, content_type, body, action, status):
    class Response:
        headers = {"Content-Type": content_type}
        def __enter__(self): return self
        def __exit__(self, *args): return False
        def read(self, limit): return body
    class Opener:
        def open(self, request, timeout):
            assert request.get_header("Authorization") == "Bearer test-token"
            assert json.loads(request.data) == {"eventId": 31, "recordId": 44}
            return Response()
    monkeypatch.setattr(consumer.urllib.request, "build_opener", lambda *args: Opener())
    result = consumer._dispatch("http://localhost", "test-token", 31, 44)
    assert result["action"] == action
    if status is not None:
        assert result["status"] == status
    assert "private detail" not in str(result)


@pytest.mark.parametrize(("decision", "expected_queue", "expected_action"), [
    ({"action": "ACK", "status": "AWAITING_REVIEW", "runId": "run"}, None, "ACK"),
    ({"action": "RETRY", "reason": "暂不可用"}, "sample.retry", "RETRY"),
    ({"action": "FAILED", "reason": "任务失败"}, "sample.failed", "FAILED"),
])
def test_consumer_acks_only_after_success_or_confirmed_routing(monkeypatch, decision, expected_queue, expected_action):
    channel = FakeChannel()
    monkeypatch.setattr(consumer.pika, "BlockingConnection", lambda _: FakeConnection(channel))
    monkeypatch.setattr(consumer, "_dispatch", lambda *args: decision)
    result = consumer.consume_one("amqp://guest:guest@localhost/", "http://localhost", "secret-token", queue="sample")
    assert result["action"] == expected_action and channel.acknowledged == [1] and channel.rejected == []
    if expected_queue:
        assert channel.published[0]["routing_key"] == expected_queue
        assert channel.published[0]["properties"].delivery_mode == 2
        assert b"secret-token" not in channel.published[0]["body"]
        assert "secret-token" not in str(channel.published[0]["properties"].headers)
    else:
        assert channel.published == []


def test_failed_retry_publish_requeues_original(monkeypatch):
    channel = FakeChannel()
    channel.fail_publish = True
    monkeypatch.setattr(consumer.pika, "BlockingConnection", lambda _: FakeConnection(channel))
    monkeypatch.setattr(consumer, "_dispatch", lambda *args: {"action": "RETRY", "reason": "暂不可用"})
    with pytest.raises(consumer.ConsumerError):
        consumer.consume_one("amqp://guest:guest@localhost/", "http://localhost", "test", queue="sample")
    assert channel.rejected == [(1, True)] and channel.acknowledged == []


def test_wrong_message_id_is_sent_to_failed_queue_without_dispatch(monkeypatch):
    channel = FakeChannel()
    channel.pending = [(object(), pika.BasicProperties(message_id="wrong"), BODY)]
    monkeypatch.setattr(consumer.pika, "BlockingConnection", lambda _: FakeConnection(channel))
    monkeypatch.setattr(consumer, "_dispatch", lambda *args: pytest.fail("invalid message must not call Agent"))
    result = consumer.consume_one("amqp://guest:guest@localhost/", "http://localhost", "test", queue="sample")
    assert result["action"] == "FAILED" and channel.published[0]["routing_key"] == "sample.failed"


@pytest.mark.skipif(not os.environ.get("TEST_RABBITMQ_URL"), reason="no isolated RabbitMQ configured")
def test_real_broker_durably_retries_then_acknowledges(monkeypatch):
    broker = os.environ["TEST_RABBITMQ_URL"]
    queue = "investment.consumer.test." + uuid.uuid4().hex
    connection = pika.BlockingConnection(pika.URLParameters(broker))
    try:
        channel = connection.channel()
        channel.queue_declare(queue=queue, durable=True)
        channel.basic_publish(exchange="", routing_key=queue, body=BODY,
                              properties=pika.BasicProperties(message_id="31", delivery_mode=2))
        monkeypatch.setattr(consumer, "_dispatch", lambda *args: {"action": "RETRY", "reason": "测试重试"})
        assert consumer.consume_one(broker, "http://localhost", "test", queue=queue, retry_seconds=1)["action"] == "RETRY"
        time.sleep(2)
        monkeypatch.setattr(consumer, "_dispatch", lambda *args: {"action": "ACK", "status": "DONE", "runId": "test"})
        assert consumer.consume_one(broker, "http://localhost", "test", queue=queue, retry_seconds=1)["action"] == "ACK"
        for name in (queue, queue + ".retry", queue + ".failed"):
            assert channel.queue_declare(queue=name, durable=True, passive=True).method.message_count == 0
    finally:
        for name in (queue, queue + ".retry", queue + ".failed"):
            try: connection.channel().queue_delete(queue=name)
            except pika.exceptions.AMQPError: pass
        connection.close()
