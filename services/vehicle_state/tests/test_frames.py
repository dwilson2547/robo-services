from app.frames import EVENT_LAG_US, FrameTable

BASE = 1_793_743_684_000_000


def rec(kind, base, session="s1", device="phone-1", **payload):
    r = {"source_session": session, "device_id": device, "captured_at": "2026-11-03T21:28:04.100000+00:00",
         "payload": {"base_timestamp_us": base, **payload}}
    if kind == "session":
        r["message_type"] = "frame"
    if kind == "imu":
        r["payload"].update(accel_m_s2={"x": 0, "y": 0, "z": 9.8}, gyro_deg_s={"x": 0, "y": 0, "z": 0})
    if kind == "can":
        r["payload"].update(signals={"EngineRPM": 800}, dt_us=1000, dbc="impala")
    return r


def receipt(base, gps=1, imu=1, can=2, **kw):
    return rec("session", base, gps=gps, imu=imu, can=can, imu_missing=0, **kw)


def feed(t, kind, base, offset, now=0.0, **kw):
    t.ingest(kind, offset, receipt(base, **kw) if kind == "session" else rec(kind, base), now)


def test_frame_is_complete_when_every_count_matches_its_receipt():
    t = FrameTable()
    feed(t, "session", BASE, 1)
    feed(t, "gps", BASE, 1)
    feed(t, "imu", BASE, 1)
    feed(t, "can", BASE, 1)
    assert t.ready(0.0) == {}  # CAN 1 of 2
    t.ingest("unknown", 1, rec("unknown", BASE), 0.0)
    [(base, frame, status)] = t.ready(0.0)[("s1", "phone-1")]
    assert (base, status, frame.data.dbc) == (BASE, "complete", "impala")


def test_a_vehicle_without_a_dbc_completes_from_its_discovery_messages():
    t = FrameTable()
    feed(t, "session", BASE, 1)
    feed(t, "gps", BASE, 1)
    feed(t, "imu", BASE, 1)
    t.ingest("discovery", 1, rec("discovery", BASE, can_id=201, data_hex="00"), 0.0)
    assert t.ready(0.0) == {}  # CAN 1 of 2
    t.ingest("discovery", 2, rec("discovery", BASE, can_id=202, data_hex="00"), 0.0)
    [(_, frame, status)] = t.ready(0.0)[("s1", "phone-1")]
    assert (status, frame.data.can, frame.data.dbc) == ("complete", [], None)


def test_short_frame_closes_incomplete_once_its_topic_moves_on():
    t = FrameTable(grace_s=10)
    feed(t, "session", BASE, 1, can=1)
    feed(t, "imu", BASE, 1)
    feed(t, "can", BASE, 1)
    assert t.ready(1.0) == {}  # GPS 0 of 1, and the GPS topic was busy a moment ago
    t.last_message_at["gps"] = 1.0
    feed(t, "gps", BASE + 1_000_000, 2, now=1.0)  # the GPS topic reaches the next frame
    [(_, _, status)] = t.ready(1.0)[("s1", "phone-1")]
    assert status == "incomplete"


def test_short_frame_closes_when_its_topic_has_been_idle_for_the_grace():
    t = FrameTable(grace_s=10)
    for k, now in (("session", 0.0), ("imu", 0.0), ("can", 0.0), ("unknown", 0.0)):
        t.last_message_at[k] = now
    feed(t, "session", BASE, 1, can=1)
    feed(t, "imu", BASE, 1)
    feed(t, "can", BASE, 1)
    t.last_message_at["gps"] = 0.0
    assert t.ready(5.0) == {}
    assert t.ready(10.0)[("s1", "phone-1")][0][2] == "incomplete"


def test_another_device_far_ahead_in_event_time_settles_a_frame():
    t = FrameTable(grace_s=1e9)
    feed(t, "session", BASE, 1, can=1)
    feed(t, "imu", BASE, 1)
    feed(t, "can", BASE, 1)
    t.ingest("gps", 9, rec("gps", BASE + EVENT_LAG_US + 1, session="s2", device="phone-2"), 0.0)
    assert t.ready(0.0)[("s1", "phone-1")][0][2] == "incomplete"


def test_frames_close_in_order_and_a_receipt_alone_is_empty():
    t = FrameTable(grace_s=10)
    feed(t, "session", BASE, 1)                    # never gets samples (e.g. before tagging)
    feed(t, "session", BASE + 1_000_000, 2, gps=0, imu=0, can=0)
    assert t.ready(0.0) == {}  # the first frame blocks the second: its topics were busy a moment ago
    for k in ("gps", "imu", "can", "unknown"):
        t.last_message_at[k] = 0.0
    out = t.ready(10.0)[("s1", "phone-1")]
    assert [(b - BASE, s) for b, _, s in out] == [(0, "empty"), (1_000_000, "complete")]


def test_untagged_proto_live_and_late_records_are_skipped():
    t = FrameTable()
    untagged = rec("gps", BASE)
    del untagged["payload"]["base_timestamp_us"]
    t.ingest("gps", 1, untagged, 0.0)
    t.ingest("gps", 2, rec("gps", BASE, session="proto-live"), 0.0)
    feed(t, "session", BASE, 3, gps=0, imu=0, can=0)
    t.close(("s1", "phone-1"), BASE)
    t.ingest("gps", 4, rec("gps", BASE), 0.0)
    assert t.open_count() == 0
    assert dict(t.skipped) == {"untagged": 1, "no session": 1, "late": 1}


def test_offsets_are_committed_only_below_the_oldest_open_frame():
    t = FrameTable()
    feed(t, "session", BASE, 10)
    feed(t, "gps", BASE, 100)
    feed(t, "gps", BASE + 1_000_000, 101)
    feed(t, "session", BASE + 1_000_000, 11)
    offsets = t.commit_offsets()
    assert (offsets["gps"], offsets["session"], offsets["imu"]) == (99, 9, None)
    t.close(("s1", "phone-1"), BASE)
    assert t.commit_offsets()["gps"] == 100
    t.close(("s1", "phone-1"), BASE + 1_000_000)
    assert t.commit_offsets()["gps"] == 101  # nothing open: everything processed


def test_a_frame_keeps_the_latest_receive_time_of_its_records():
    t = FrameTable()
    for offset, received in enumerate(["2026-11-03T21:28:05.2Z", "2026-11-03T21:28:07.9+00:00", "2026-11-03T21:28:06Z"]):
        t.ingest("gps", offset, {**rec("gps", BASE), "received_at": received}, 0.0)
    t.ingest("imu", 0, rec("imu", BASE), 0.0)  # no received_at: ignored
    assert t.frames[("s1", "phone-1")][BASE].received_at == "2026-11-03T21:28:07.9+00:00"
