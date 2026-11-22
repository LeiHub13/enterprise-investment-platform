import json
import os
import uuid

import pika
import pytest

from collector.events import publisher


EVENT = {"id": 11, "eventType": "COLLECTOR_RECORD_CREATED", "sourceRecordId": 22,
         "enterpriseId": 33, "source": "QCC", "recordType": "RECRUITMENT",
         "isDemo": True, "leaseToken": "private-lease"}


def test_publisher_confirms_and_acknowledges_without_exposing_lease(monkeypatch):
    sent, acknowledged = [], []
    class Channel:
        def queue_declare(self, **kwargs): pass
        def confirm_delivery(self): pass
        def basic_publish(self, **kwargs): sent.append(kwargs)
    class Connection:
        is_open = True
        def channel(self): return Channel()
        def close(self): self.is_open = False
    monkeypatch.setattr(publisher.config, "COLLECTOR_TOKEN", "test")
    monkeypatch.setattr(publisher.pika, "BlockingConnection", lambda _: Connection())
    monkeypatch.setattr(publisher.uploader, "claim_outbox", lambda _: [EVENT])
    monkeypatch.setattr(publisher.uploader, "ack_outbox", lambda event_id, token: acknowledged.append((event_id, token)))
    assert publisher.publish_pending("amqp://guest:guest@localhost/") == 1
    assert acknowledged == [(11, "private-lease")]
    assert sent[0]["properties"].message_id == "11" and sent[0]["mandatory"] is True
    assert "leaseToken" not in json.loads(sent[0]["body"])


def test_publish_failure_releases_lease_for_retry(monkeypatch):
    retried = []
    class Channel:
        def queue_declare(self, **kwargs): pass
        def confirm_delivery(self): pass
        def basic_publish(self, **kwargs): raise RuntimeError("broker failure")
    class Connection:
        is_open = True
        def channel(self): return Channel()
        def close(self): self.is_open = False
    monkeypatch.setattr(publisher.config, "COLLECTOR_TOKEN", "test")
    monkeypatch.setattr(publisher.pika, "BlockingConnection", lambda _: Connection())
    monkeypatch.setattr(publisher.uploader, "claim_outbox", lambda _: [EVENT])
    monkeypatch.setattr(publisher.uploader, "retry_outbox", lambda event_id, token: retried.append((event_id, token)))
    with pytest.raises(publisher.PublishError):
        publisher.publish_pending("amqp://guest:guest@localhost/")
    assert retried == [(11, "private-lease")]


@pytest.mark.skipif(not os.environ.get("TEST_RABBITMQ_URL"), reason="no test RabbitMQ configured")
def test_real_broker_confirms_persistent_event(monkeypatch):
    broker = os.environ["TEST_RABBITMQ_URL"]
    queue = "investment.collector.test." + uuid.uuid4().hex
    monkeypatch.setattr(publisher.config, "COLLECTOR_TOKEN", "test")
    monkeypatch.setattr(publisher.uploader, "claim_outbox", lambda _: [EVENT])
    acknowledged = []
    monkeypatch.setattr(publisher.uploader, "ack_outbox", lambda event_id, token: acknowledged.append(event_id))
    try:
        assert publisher.publish_pending(broker, queue=queue) == 1
        connection = pika.BlockingConnection(pika.URLParameters(broker))
        try:
            channel = connection.channel()
            method, props, body = channel.basic_get(queue=queue, auto_ack=False)
            assert method is not None and props.message_id == "11" and props.delivery_mode == 2
            assert json.loads(body)["isDemo"] is True and acknowledged == [11]
            channel.basic_ack(method.delivery_tag)
            channel.queue_delete(queue=queue)
        finally:
            connection.close()
    except Exception:
        # The queue name is unique to this test; best-effort cleanup on failure.
        connection = pika.BlockingConnection(pika.URLParameters(broker))
        try:
            connection.channel().queue_delete(queue=queue)
        finally:
            connection.close()
        raise
