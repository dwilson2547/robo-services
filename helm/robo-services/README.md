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
- Optional Flink jobs: `speed-derivation`, `lap-segmentation`, and `track-position`

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
```

To temporarily render the legacy in-chart ingress pieces for local-only testing:

```bash
helm template robo-services ./helm/robo-services \
  --set receiver.enabled=true \
  --set mosquitto.enabled=true
```
