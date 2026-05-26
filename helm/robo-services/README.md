# robo-services Helm chart

This chart packages the prototype UDP receiver for deployment into the
`robo-services` namespace while treating Iggy as an external dependency in the
`pub-sub` namespace.

## Default shape

- Namespace: `robo-services`
- Deployment: `kreceiver`
- Service: `kreceiver` (`LoadBalancer`, UDP 5514)
- Secret: `kreceiver-secret`
- External broker target: `iggy.pub-sub.svc.cluster.local:8090`

## Example

```bash
helm template robo-services ./helm/robo-services
helm upgrade --install robo-services ./helm/robo-services \
  --set secret.iggyConnectionString='iggy+tcp://iggy:replace-me@iggy.pub-sub.svc.cluster.local:8090'
```
