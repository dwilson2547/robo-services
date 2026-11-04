import asyncio

from app.dbc_resolver import NEGATIVE_TTL, PENDING, PENDING_TTL, POSITIVE_TTL, Dbc, DbcResolver, Missing, NoDbc

DBC_A = '''VERSION ""
BU_: X
BO_ 485 SPEED: 2 X
 SG_ kph : 0|16@1+ (0.1,0) [0|6553] "kph" X
'''


class Clock:
    t = 0.0

    def __call__(self):
        return self.t


def run(coro):
    return asyncio.run(coro)


def resolver(answers, clock):
    calls = []

    def fetch(device, session):
        calls.append((device, session))
        a = answers.get(session)
        if isinstance(a, Exception):
            raise a
        return a

    return DbcResolver(Dbc("default", {}), fetch, clock=clock), calls


def test_session_gets_its_vehicles_dbc_compiled_once_and_cached():
    clock = Clock()
    answers = {"1": {"name": "impala", "sha256": "aa", "content": DBC_A}, "2": {"name": "impala", "sha256": "aa", "content": DBC_A}}
    r, calls = resolver(answers, clock)
    a = run(r.for_session("phone", "1"))
    assert (a.name, list(a.messages)) == ("impala", [485])
    assert run(r.for_session("phone", "1")) is a  # cached: one registry call
    assert run(r.for_session("phone", "2")) is a  # same content, compiled once
    assert calls == [("phone", "1"), ("phone", "2")]
    clock.t += POSITIVE_TTL + 1
    run(r.for_session("phone", "1"))
    assert len(calls) == 3  # asked again after the TTL (a vehicle's DBC can change)


def test_vehicle_without_a_dbc_goes_to_discovery_and_is_asked_again():
    clock = Clock()
    r, calls = resolver({"1": Missing("vehicle has no DBC"), "2": Missing("session has no vehicle")}, clock)
    assert run(r.for_session("phone", "1")) == NoDbc("vehicle has no DBC")
    assert run(r.for_session("phone", "2")) == NoDbc("session has no vehicle")
    run(r.for_session("phone", "1"))
    assert len(calls) == 2
    clock.t += NEGATIVE_TTL + 1
    run(r.for_session("phone", "1"))
    assert len(calls) == 3  # a DBC can be assigned mid-session


def test_unregistered_session_is_pending_and_asked_again_within_a_second():
    clock = Clock()
    answers = {"1": Missing("session not found")}
    r, calls = resolver(answers, clock)
    assert run(r.for_session("phone", "1")) is PENDING
    clock.t += PENDING_TTL + 0.01
    answers["1"] = {"name": "impala", "sha256": "aa", "content": DBC_A}
    assert run(r.for_session("phone", "1")).name == "impala"
    assert len(calls) == 2


def test_registry_errors_are_retried_until_it_answers():
    slept = []

    async def sleep(s):
        slept.append(s)

    failures = [OSError("down")] * 3
    def fetch(_device, _session):
        if failures:
            raise failures.pop()
        return {"name": "impala", "sha256": "aa", "content": DBC_A}

    r = DbcResolver(Dbc("default", {}), fetch, clock=Clock(), sleep=sleep)
    assert run(r.for_session("phone", "1")).name == "impala"
    assert slept == [1.0, 2.0, 4.0]


def test_a_dbc_that_does_not_parse_goes_to_discovery_not_the_default():
    r, _ = resolver({"2": {"name": "bad", "sha256": "bb", "content": "not a dbc {{{"}}, Clock())
    assert run(r.for_session("phone", "2")) == NoDbc("DBC 'bad' does not parse")


def test_frames_without_a_session_never_ask():
    r, calls = resolver({}, Clock())
    assert run(r.for_session("logger", "proto-live")).name == "default"
    assert run(r.for_session("logger", "")).name == "default"
    assert calls == []
