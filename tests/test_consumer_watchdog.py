import asyncio

from can_pub_sub_probe.consumer_watchdog import ProgressWatchdog, watch


def test_idle_topic_never_stalls():
    dog = ProgressWatchdog(stall_after=120, max_failures=4)
    for t in range(0, 10_000, 30):
        assert dog.observe(770, 769, t) is None


def test_growth_without_progress_stalls_after_the_grace():
    dog = ProgressWatchdog(stall_after=120, max_failures=4)
    assert dog.observe(659 + 1, 659, 0) is None  # caught up, then idle for hours
    assert dog.observe(660, 659, 7200) is None
    assert dog.observe(700, 659, 7230) is None  # a capture arrives: behind from here
    assert dog.observe(740, 659, 7320) is None
    reason = dog.observe(770, 659, 7350)
    assert reason and "659" in reason


def test_progress_resets_the_clock():
    dog = ProgressWatchdog(stall_after=120, max_failures=4)
    dog.observe(100, 99, 0)
    assert dog.observe(200, 99, 30) is None
    assert dog.observe(300, 250, 140) is None  # moved: not stalled even though behind
    assert dog.observe(400, 299, 260) is None
    assert dog.observe(400, 399, 400) is None


def test_starting_with_nothing_consumed_and_a_growing_topic_stalls():
    dog = ProgressWatchdog(stall_after=60, max_failures=4)
    assert dog.observe(100, None, 0) is None
    assert dog.observe(110, None, 30) is None
    assert dog.observe(120, None, 90) is not None


def test_consecutive_failures_stall_and_a_success_resets_them():
    dog = ProgressWatchdog(stall_after=120, max_failures=3)
    assert dog.failed() is None
    assert dog.failed() is None
    dog.observe(10, 9, 0)
    assert dog.failed() is None
    assert dog.failed() is None
    assert dog.failed() is not None


def test_watch_calls_on_stall_once_and_returns():
    counts = iter([10, 20, 30, 40, 50])
    stalls = []
    now = [0.0]

    async def read():
        now[0] += 30
        return next(counts)

    async def main():
        stop = asyncio.Event()
        await asyncio.wait_for(
            watch(read, lambda: 9, stalls.append, stop, interval=0.01, stall_after=60, clock=lambda: now[0]),
            timeout=2,
        )

    asyncio.run(main())
    assert len(stalls) == 1


def test_watch_reports_each_reading():
    readings = []

    async def read():
        return 42

    async def main():
        stop = asyncio.Event()
        task = asyncio.create_task(watch(read, lambda: 40, lambda r: None, stop, interval=0.01,
                                         on_reading=lambda c, o: readings.append((c, o))))
        await asyncio.sleep(0.05)
        stop.set()
        await task

    asyncio.run(main())
    assert readings and set(readings) == {(42, 40)}


def test_watch_counts_read_errors_and_stops_on_event():
    stalls = []

    async def broken():
        raise RuntimeError("Unauthenticated")

    async def main():
        stop = asyncio.Event()
        await asyncio.wait_for(watch(broken, lambda: None, stalls.append, stop, interval=0.01, max_failures=3), 2)
        assert stalls and "3 times" in stalls[0]
        stalls.clear()
        stop.set()
        await asyncio.wait_for(watch(broken, lambda: None, stalls.append, stop, interval=0.01), 2)

    asyncio.run(main())
    assert stalls == []
