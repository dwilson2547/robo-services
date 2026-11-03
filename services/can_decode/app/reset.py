"""Set where the can-decode consumer group reads from next.

    python -m app.reset latest     # skip everything already on the topic
    python -m app.reset earliest   # decode everything still retained
    python -m app.reset <offset>   # resume after this offset

Run it with can-decode scaled to 0: the topic has one partition, which belongs
to whichever group member joined first, so a running service would keep it.
"""

from __future__ import annotations

import asyncio
import logging
import sys

from apache_iggy import AutoCommit, PollingStrategy

from .main import PARTITION, Settings, connect, ensure_topic, join_group, maybe_await

LOG = logging.getLogger("can-decode-reset")


async def reset(target: str) -> None:
    settings = Settings()
    client = await connect(settings)
    await ensure_topic(client, settings.stream, settings.in_topic)
    consumer = await join_group(client, settings, AutoCommit.Disabled())
    if target == "earliest":
        await maybe_await(consumer.delete_offset(PARTITION))
        LOG.info("group %r on %s: offset deleted, next read starts at the first retained message",
                 settings.group, settings.in_topic)
        return
    if target == "latest":
        last = await client.poll_messages(
            settings.stream, settings.in_topic, PARTITION, PollingStrategy.Last(), 1, False
        )
        if not last:
            await maybe_await(consumer.delete_offset(PARTITION))
            LOG.info("topic %s is empty; group %r starts at the beginning", settings.in_topic, settings.group)
            return
        offset = last[-1].offset()
    else:
        offset = int(target)
    await maybe_await(consumer.store_offset(offset, PARTITION))
    LOG.info("group %r on %s: next read starts after offset %d", settings.group, settings.in_topic, offset)


def main() -> int:
    logging.basicConfig(level="INFO")
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    asyncio.run(reset(sys.argv[1]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
