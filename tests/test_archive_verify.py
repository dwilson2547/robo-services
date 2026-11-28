import asyncio
import io
import json
from types import SimpleNamespace

import pytest

pytest.importorskip("apache_iggy")
pytest.importorskip("pyarrow")
pytest.importorskip("boto3")

from can_pub_sub_probe import archive_verify as V  # noqa: E402
from can_pub_sub_probe.iggy_backend import _encode_message  # noqa: E402


class FakeS3:
    def __init__(self):
        self.objects = {}

    def put_object(self, Bucket, Key, Body, **_):
        self.objects[Key] = Body

    def get_object(self, Bucket, Key):
        return {"Body": io.BytesIO(self.objects[Key])}

    def get_paginator(self, _):
        objects = self.objects

        class Pages:
            def paginate(self, Bucket, Prefix):
                return [{"Contents": [{"Key": k} for k in objects if k.startswith(Prefix)]}]

        return Pages()


class FakeIggy:
    def __init__(self, payloads):
        self.messages = [SimpleNamespace(offset=lambda i=i: i, payload=lambda p=p: p)
                         for i, p in enumerate(_encode_message(json.dumps(x).encode(), {}) for x in payloads)]

    async def get_topic(self, stream, topic):
        return SimpleNamespace(messages_count=len(self.messages))

    async def poll_messages(self, stream, topic, partition, strategy, count, auto_commit):
        assert auto_commit is False
        start = getattr(self, "next", 0)
        batch = self.messages[start:start + count]
        self.next = start + len(batch)
        if not batch:
            self.next = 0  # the next pass starts over
        return batch


SETTINGS = SimpleNamespace(iggy_stream="s", s3_bucket="b", key_prefix="raw-telemetry")
RECORDS = [{"source_session": "1", "captured_at": "2026-09-01T10:00:00+00:00", "n": n} for n in (1, 2, 2, 3)]


def test_missing_records_are_found_with_multiplicity_then_backfilled():
    iggy, s3, lines = FakeIggy(RECORDS), FakeS3(), []
    check = lambda backfill: asyncio.run(V.run(SETTINGS, ["t"], backfill, client=iggy, s3=s3, log=lines.append))
    assert check(False) == 1 and not s3.objects  # report only writes nothing
    assert "t: 4 messages, 1 sessions, 4 not archived in 1 sessions" in lines
    assert check(True) == 0
    assert check(False) == 0 and lines[-1] == "every retained record is archived"
    # drop to one copy of the repeated record: exactly one is missing
    s3.objects.clear()
    iggy_less = FakeIggy([RECORDS[0], RECORDS[1], RECORDS[3]])
    asyncio.run(V.run(SETTINGS, ["t"], True, client=iggy_less, s3=s3, log=lines.append))
    assert check(False) == 1
    assert "t: 4 messages, 1 sessions, 1 not archived in 1 sessions" in lines
