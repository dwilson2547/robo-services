from __future__ import annotations

from datetime import UTC, datetime

from apache_iggy import PollingStrategy

from can_pub_sub_probe.archive_writer import (
    build_buffer_key,
    build_initial_polling_strategy,
    build_object_key,
    normalize_record,
)
from can_pub_sub_probe.iggy_backend import IggyMessage


class _FakeBackend:
    def __init__(self, latest_offset: int | None):
        self._latest_offset = latest_offset

    def latest_offset(self, topic: str) -> int | None:
        assert topic == "telemetry.raw.gps"
        return self._latest_offset


def test_build_object_key_partitions_topic_and_session() -> None:
    key = build_object_key(
        prefix="raw-telemetry",
        topic="telemetry.raw.gps",
        source_session="session/one",
        event_date="2026-06-20",
        event_hour="09",
    )
    assert key.startswith(
        "raw-telemetry/topic=telemetry.raw.gps/source_session=session_one/date=2026-06-20/hour=09/part-"
    )
    assert key.endswith(".parquet")


def test_normalize_record_extracts_partition_fields() -> None:
    archived_at = datetime(2026, 6, 20, 9, 30, tzinfo=UTC)
    message = IggyMessage(
        _payload=(
            b'{"device_id":"DEVICE-1","source_session":"session-1","captured_at":"2026-06-20T09:17:01Z",'
            b'"message_type":"telemetry","source":"gps","latitude":37.0,"longitude":-112.0}'
        ),
        headers={"x-test": "1"},
    )

    record = normalize_record(topic="telemetry.raw.gps", message=message, archived_at=archived_at)
    key = build_buffer_key(record)

    assert key.topic == "telemetry.raw.gps"
    assert key.source_session == "session-1"
    assert key.date == "2026-06-20"
    assert key.hour == "09"
    assert record["device_id"] == "DEVICE-1"
    assert record["headers_json"] == '{"x-test":"1"}'


def test_build_initial_polling_strategy_defaults_latest_to_last() -> None:
    strategy = build_initial_polling_strategy(_FakeBackend(41), "telemetry.raw.gps", "latest")
    assert isinstance(strategy, type(PollingStrategy.Offset(0)))
    assert strategy.value == 42


def test_build_initial_polling_strategy_uses_zero_offset_for_empty_topic() -> None:
    strategy = build_initial_polling_strategy(_FakeBackend(None), "telemetry.raw.gps", "latest")
    assert isinstance(strategy, type(PollingStrategy.Offset(0)))
    assert strategy.value == 0


def test_build_initial_polling_strategy_earliest_maps_to_first() -> None:
    assert isinstance(
        build_initial_polling_strategy(_FakeBackend(None), "telemetry.raw.gps", "earliest"),
        type(PollingStrategy.First()),
    )
