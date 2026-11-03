import asyncio

from app.dbc_resolver import NEGATIVE_TTL, POSITIVE_TTL, Dbc, DbcResolver

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


def test_no_answer_uses_the_default_and_asks_again_soon():
    clock = Clock()
    r, calls = resolver({"1": None}, clock)
    assert run(r.for_session("phone", "1")).name == "default"
    run(r.for_session("phone", "1"))
    assert len(calls) == 1
    clock.t += NEGATIVE_TTL + 1
    run(r.for_session("phone", "1"))
    assert len(calls) == 2  # session_start may just not have been processed yet


def test_registry_errors_and_bad_dbcs_fall_back_to_default():
    r, _ = resolver({"1": OSError("down"), "2": {"name": "bad", "sha256": "bb", "content": "not a dbc {{{"}}, Clock())
    assert run(r.for_session("phone", "1")).name == "default"
    assert run(r.for_session("phone", "2")).name == "default"


def test_frames_without_a_session_never_ask():
    r, calls = resolver({}, Clock())
    assert run(r.for_session("logger", "proto-live")).name == "default"
    assert run(r.for_session("logger", "")).name == "default"
    assert calls == []
