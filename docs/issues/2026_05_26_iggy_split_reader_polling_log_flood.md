# IggyPartitionSplitReader busy-polls when idle, flooding TaskManager logs and masking real output

**Date:** 2026-05-26  
**Component:** `helm/robo-services/templates/speed-job-flinkdeployment.yaml` — `logConfiguration`; `org.apache.iggy.connector.flink.source.IggyPartitionSplitReader` (connector internals)  
**Severity:** Medium — the job was functionally healthy (STABLE, RUNNING, checkpoints completing) but the log flood made it appear broken and completely hid all other TaskManager output

---

## Observed symptom

After the speed derivation Flink job was deployed and stabilised, the TaskManager pod logs
contained exclusively `IggyPartitionSplitReader` polling messages at INFO level, repeating
thousands of times per second:

```
2026-05-26 06:04:11,506 INFO  o.a.i.c.f.s.IggyPartitionSplitReader - IggyPartitionSplitReader: Polling partition=0, strategy=NEXT (consumer-group-managed), batchSize=100
2026-05-26 06:04:11,506 INFO  o.a.i.c.f.s.IggyPartitionSplitReader - IggyPartitionSplitReader: Polled partition=0, messagesCount=0, currentOffset=16084
```

No other TaskManager log output was visible — startup messages, task lifecycle events, sink
activity, and GC warnings were all buried or dropped. The TaskManager appeared to be in an
unstable or perpetually "progressing" state from the operator's perspective.

---

## Root cause

### No idle backoff in IggyPartitionSplitReader

The Iggy Flink connector's `IggyPartitionSplitReader.fetch()` returns immediately with an empty
result set when there are no new messages on the topic partition. Flink's `SourceReaderBase`
framework then calls `fetch()` again immediately, creating a tight busy-wait loop with no sleep
or backoff between polls.

When the GPS source is quiet (no new messages arriving), the connector spins at full speed,
producing two INFO log lines per iteration — one for "Polling" and one for "Polled … messagesCount=0".
At the observed rate (multiple iterations per millisecond), this saturated the TM log output
completely.

The `IggySourceBuilder` exposes no `pollInterval` or `idleSleepMs` configuration:

```java
// IggySourceBuilder only exposes:
.setPollBatchSize(long)
// No poll interval or idle sleep option available in connector 0.8.0
```

### Root job and deployment were healthy

The `FlinkDeployment` status showed `lifecycleState: STABLE`, `jobStatus.state: RUNNING`, and 26
successive checkpoints completing normally. The pod had 0 restarts. The log flood was misleading —
it was a logging/observability problem, not a runtime failure.

---

## Troubleshooting steps taken

1. **Checked pod status and FlinkDeployment CR** — all pods Running/Ready (0 restarts), operator
   reported `STABLE` / `RUNNING` / `READY`. Ruled out actual crash loop or operator failure.

2. **Read TaskManager logs** — 100% `IggyPartitionSplitReader` Polling/Polled pairs at INFO,
   sub-millisecond cadence. No other lines present. Confirmed the TM log buffer was completely
   saturated.

3. **Filtered TM logs excluding polling class** — zero lines returned. Confirmed no other output
   of any kind was escaping the flood.

4. **Checked JobManager logs** — healthy: checkpoint triggers and completions (checkpoints 1–26)
   every 60 seconds, no errors or warnings.

5. **Inspected IggySourceBuilder API** (`javap -p`) — confirmed no poll interval or idle sleep
   configuration is available in connector version 0.8.0.

6. **Confirmed job was functionally working** — derived topic `telemetry.derived.speed` had been
   receiving publishes, and the user had observed output on the topic. Issue was purely
   observability.

---

## Fix

### `helm/robo-services/templates/speed-job-flinkdeployment.yaml` — add `logConfiguration` to suppress polling spam

Added a `logConfiguration` block to the `FlinkDeployment` spec that sets
`IggyPartitionSplitReader` to WARN level while keeping all other loggers at INFO. The Flink
operator propagates this as a `log4j-console.properties` override on both JobManager and
TaskManager pods.

```yaml
# before: no logConfiguration section

# after:
  logConfiguration:
    "log4j-console.properties": |
      rootLogger.level = INFO
      rootLogger.appenderRef.console.ref = ConsoleAppender
      appender.console.name = ConsoleAppender
      appender.console.type = CONSOLE
      appender.console.layout.type = PatternLayout
      appender.console.layout.pattern = %d{yyyy-MM-dd HH:mm:ss,SSS} %-5p %-60c %marker - %m%n
      logger.iggy-split-reader.name = org.apache.iggy.connector.flink.source.IggyPartitionSplitReader
      logger.iggy-split-reader.level = WARN
```

This does not add idle sleep to the connector (the busy-poll still occurs at the CPU level) but
restores log visibility and eliminates the observability problem. If CPU burn from the tight loop
becomes a concern in production, the correct long-term fix is to either upgrade to a connector
version that supports a configurable poll interval, or replace the source with a custom
`RichSourceFunction` that includes explicit `Thread.sleep()` on empty fetch.

---

## Files changed

- `helm/robo-services/templates/speed-job-flinkdeployment.yaml` — added `logConfiguration` with `log4j-console.properties` override

---

## Follow-up 2026-10-21: the busy-poll pinned every taskmanager CPU

The log suppression above hid the loop but it kept running. Measured 2026-10-21: all four
Iggy-sourced jobs (speed, lap, track-position, track-position-timescale-sink) sat at their 500m
CPU limit, CFS-throttled up to 99% of periods, flat for the full Prometheus retention window,
while every vertex reported 0 records read. The Iggy server (`pub-sub`) burned ~1 core
answering the empty polls. Memory was fine (~380 MiB working set of 1 GiB).

### Corrected root cause

The mechanism is not `SourceReaderBase`/`fetch()` — `IggySourceReader` implements `SourceReader`
directly. On an empty poll `pollNext()` returns `NOTHING_AVAILABLE`, but `isAvailable()` returns
an already-completed future ("For simplicity, we return completed future and rely on polling"),
so Flink re-enters `pollNext()` immediately and each iteration is a blocking `pollMessages`
round-trip. Thread dump: the `Source:` task thread parked in
`IggyPartitionSplitReader.poll` → `CompletableFuture.join`, with its Netty
`multiThreadIoEventLoopGroup` threads holding most of the CPU time. Unchanged in connector 0.9.0.

### Fix

`derivations/iggy_source_support` — `IdleBackoffSource.wrap(source)` delegates the whole
`Source`/`SourceReader` contract and, after a `NOTHING_AVAILABLE` poll, returns an
`isAvailable()` future that completes after 100 ms. Flink waits on that future through the task
mailbox, so checkpoints still run during the backoff. Worst-case added wake-up latency on an
idle stream is one backoff interval. Each job wraps its `IggySource` with it; each Dockerfile
`mvn install`s the module before building the job.

The log override is kept: with the wrapper the reader still logs at INFO ~10×/s per idle source.
