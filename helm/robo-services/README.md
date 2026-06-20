# robo-services Helm chart

This chart packages the robo-services-side workloads that still live in the
`robo-services` namespace: the registry service plus the Flink derivation jobs.
The pub/sub ingress components (`kreceiver`, Mosquitto, and Iggy) are treated as
external dependencies in the `pub-sub` namespace.

## Default shape

- Namespace: `robo-services`
- Secret: `kreceiver-secret`
- External broker target: `iggy.pub-sub.svc.cluster.local:8090`
- External MQTT broker target: `mosquitto.pub-sub.svc.cluster.local:1883`
- Optional pipeline workers: `speed-derivation`, `lap-segmentation`, `track-position`, `track-position-timescale-sink`, and `parquet-archive-writer`

## Example

```bash
helm template robo-services ./helm/robo-services
helm upgrade --install robo-services ./helm/robo-services \
  --set secret.iggyConnectionString='iggy+tcp://iggy:replace-me@iggy.pub-sub.svc.cluster.local:8090'
```

To render the speed derivation too:

```bash
helm template robo-services ./helm/robo-services \
  --set secret.iggyConnectionString='iggy+tcp://iggy:replace-me@iggy.pub-sub.svc.cluster.local:8090' \
  --set speedJob.enabled=true

To render the Timescale sink POC as well, create a `timescaledb-credentials` Secret in the
`robo-services` namespace with `POSTGRES_USER` and `POSTGRES_PASSWORD`, then enable:

```bash
helm template demo ./helm/robo-services \
  --set trackPositionTimescaleSinkJob.enabled=true
```

For the raw immutable archive path, create a `race-logger-bucket-credentials` Secret in the
`robo-services` namespace with `S3_ACCESS_KEY` and `S3_SECRET_KEY`, then enable:

```bash
helm template demo ./helm/robo-services \
  --set archiveWriter.enabled=true
```
```

To temporarily render the legacy in-chart ingress pieces for local-only testing:

```bash
helm template robo-services ./helm/robo-services \
  --set receiver.enabled=true \
  --set mosquitto.enabled=true
```
