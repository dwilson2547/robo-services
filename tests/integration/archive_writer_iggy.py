"""The real archive writer against a real Iggy 0.8.0, with a folder as the bucket.

    docker run -d --rm --name iggy-test -p 127.0.0.1:58090:8090 --security-opt seccomp=unconfined \
      --cap-add SYS_NICE --ulimit memlock=-1:-1 -e IGGY_ROOT_USERNAME=test -e IGGY_ROOT_PASSWORD=testpass123 \
      -e IGGY_TCP_ADDRESS=0.0.0.0:8090 apache/iggy:0.8.0
    docker run --rm --network host -v $PWD:/repo:ro -e PYTHONPATH=/repo/src --entrypoint python \
      dwilson2547/robo-services-archive-writer:<tag> /repo/tests/integration/archive_writer_iggy.py

Every message sent must end up archived through restarts, kills, upload failures,
a long quiet spell and a second copy. Not covered here: an Iggy server restart.
"""

import asyncio
import collections
import json
import logging
import os
import pathlib
import signal
import subprocess
import sys
import tempfile
import time

import pyarrow.parquet as pq
from apache_iggy import IggyClient, SendMessage

from can_pub_sub_probe import archive_writer as w
from can_pub_sub_probe.iggy_backend import _encode_message

CS = os.getenv("TEST_IGGY", "iggy+tcp://test:testpass123@127.0.0.1:58090")
STREAM = f"it{int(time.time())}"
TOPICS = ("t", "u")


class FolderBucket:
    def __init__(self, root):
        self.root = pathlib.Path(root)

    def put_object(self, Bucket, Key, Body, **_):
        if (self.root / "FAIL").exists():
            raise OSError("bucket down")
        path = self.root / Key.replace("/", "__")
        path.with_suffix(".tmp").write_bytes(Body)
        path.with_suffix(".tmp").rename(path)


async def writer(root):
    stop = asyncio.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        asyncio.get_running_loop().add_signal_handler(sig, stop.set)
    await w.run(w.ArchiveWriterSettings.from_env(), FolderBucket(root), stop)


async def main() -> int:
    out = pathlib.Path(tempfile.mkdtemp())
    log = pathlib.Path(tempfile.mkdtemp())
    env = dict(os.environ, IGGY_CONNECTION_STRING=CS, IGGY_STREAM=STREAM, ARCHIVE_WRITER_TOPICS=",".join(TOPICS),
               ARCHIVE_WRITER_S3_ACCESS_KEY="x", ARCHIVE_WRITER_S3_SECRET_KEY="x",
               ARCHIVE_WRITER_MAX_SECONDS_PER_FILE="3", ARCHIVE_WRITER_IDLE_SLEEP_SECONDS="0.3")
    client = IggyClient.from_connection_string(CS)
    await client.connect()
    await client.create_stream(STREAM)
    for topic in TOPICS:
        await client.create_topic(STREAM, topic, partitions_count=1)
    sent = collections.Counter()
    failures = []

    async def send(n):
        for topic in TOPICS:
            for start in range(sent[topic], sent[topic] + n, 100):
                await client.send_messages(STREAM, topic, 0, [
                    SendMessage(_encode_message(json.dumps({"source_session": str(i // 1000), "n": i}).encode(), {}))
                    for i in range(start, min(start + 100, sent[topic] + n))])
            sent[topic] += n

    def start(name="w"):
        return subprocess.Popen([sys.executable, __file__, "writer", str(out)], env=env,
                                stderr=open(log / f"{name}.log", "a"))

    def archived():
        got = collections.defaultdict(list)
        for file in out.glob("*.parquet"):
            table = pq.read_table(file, columns=["topic", "iggy_offset"], partitioning=None)
            for topic, offset in zip(table.column("topic").to_pylist(), table.column("iggy_offset").to_pylist()):
                got[topic].append(offset)
        return got

    async def check(label, wait_s=45):
        until = time.monotonic() + wait_s
        while time.monotonic() < until and any(len(set(archived()[t])) < sent[t] for t in TOPICS):
            await asyncio.sleep(0.5)
        got = archived()
        missing = {t: sent[t] - len(set(got[t])) for t in TOPICS}
        extra = {t: len(got[t]) - len(set(got[t])) for t in TOPICS}
        ok = not any(missing.values())
        print(f"{'OK     ' if ok else 'MISSING'} {label}: sent {sent['t']} missing {missing} copies {extra}", flush=True)
        if not ok:
            failures.append(label)

    def stop(process):
        process.send_signal(signal.SIGTERM)
        process.wait(30)

    await send(3000)
    p = start()
    await check("backlog present at first start")
    await asyncio.sleep(2)
    await send(700)
    await check("new messages while running")
    stop(p)
    await send(900)
    p = start()
    await check("sent while stopped")
    await send(130)
    await asyncio.sleep(1)
    p.kill()
    p.wait()
    await send(60)
    p = start()
    await check("hard kill with records only in memory")
    (out / "FAIL").touch()
    await send(800)
    await asyncio.sleep(6)
    p.kill()
    p.wait()
    (out / "FAIL").unlink()
    p = start()
    await check("killed while the bucket was down")
    stop(p)
    p = start()
    await asyncio.sleep(100)  # caught up and quiet well past every internal wait
    if p.poll() is not None:
        failures.append("writer exited while idle")
    await send(1)
    await check("one message after 100 s idle")
    await send(500)
    await check("then a burst")
    (out / "FAIL").touch()
    await send(300)
    await asyncio.sleep(6)
    (out / "FAIL").unlink()
    await check("bucket down and back, same process")
    stop(p)
    p = start()
    await asyncio.sleep(5)
    second = start("second")
    for _ in range(60):
        await send(2)
        await asyncio.sleep(2)
        if second.poll() is not None:
            break
    gave_up = second.poll() is not None and p.poll() is None
    print(f"{'OK     ' if gave_up else 'FAIL   '} a second copy gives up and the first carries on", flush=True)
    if not gave_up:
        failures.append("second copy")
        second.kill()
    await check("nothing missing with a second copy around")
    stop(p)
    print("FAILED: " + ", ".join(failures) if failures else "all passed")
    return 1 if failures else 0


if __name__ == "__main__":
    logging.basicConfig(level="INFO")
    if len(sys.argv) > 2 and sys.argv[1] == "writer":
        asyncio.run(writer(sys.argv[2]))
    else:
        sys.exit(asyncio.run(main()))
