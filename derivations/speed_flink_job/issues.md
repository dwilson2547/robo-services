● Pipeline TL;DR: the Flink job subscribes to telemetry.raw.gps in Iggy, joins the speed-derivation consumer group, reads each GPS wrapper message, decodes payload_b64, extracts the inner normalized GPS payload, pulls payload.ground_speed_kph, then keys by device_id and computes an average over a 10-second event-time tumbling window with 5 seconds of allowed lateness/out-of-orderness. Each completed window becomes one derived record published to telemetry.derived.speed.

  Why the cadence looks slower than 1s: it is not a per-message passthrough. It only emits when a window closes, and the event-time watermark plus the sink flush behavior can make that land more like every 5-10 seconds instead of every second.

  Where it broke along the way:

   1. The Iggy consumer group and derived topic were missing.
   2. The source payload was not the normalized GPS JSON directly; it was a wrapper with headers and payload_b64.
   3. The Iggy Flink connector’s built-in JSON source/sink paths were colliding with Jackson versions in the Flink runtime.

  Current likely issue: the source side is mostly working now, but the sink/task side is still unstable. Earlier failures showed the built-in Iggy sink path blowing up in its internal HTTP/Jackson stack, which is why I swapped to a custom TCP sink. If you’re investigating live, I’d focus on:

   - whether speed-derivation-taskmanager-1-1 is still restarting
   - the first exception around compute-average-speed -> speed-sink: Writer
   - whether derived records are emitted by the window function but fail during send
   - whether the latest image/tag in the FlinkDeployment matches the newest rollout

  Files to inspect:

   - derivations/speed_flink_job/src/main/java/io/github/dwilson2547/roboservices/derivations/speed/SpeedDerivationJob.java
   - helm/robo-services/values.yaml
   - helm/robo-services/templates/speed-job-flinkdeployment.yaml