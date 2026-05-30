package io.github.dwilson2547.roboservices.derivations.lap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.KeyedCoProcessFunction;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;
import org.apache.iggy.client.blocking.ConsumerGroupsClient;
import org.apache.iggy.client.blocking.TopicsClient;
import org.apache.iggy.client.blocking.tcp.IggyTcpClient;
import org.apache.iggy.connector.config.IggyConnectionConfig;
import org.apache.iggy.connector.flink.source.IggySource;
import org.apache.iggy.connector.serialization.DeserializationSchema;
import org.apache.iggy.connector.serialization.RecordMetadata;
import org.apache.iggy.connector.serialization.TypeDescriptor;
import org.apache.iggy.identifier.ConsumerId;
import org.apache.iggy.identifier.StreamId;
import org.apache.iggy.identifier.TopicId;
import org.apache.iggy.message.Message;
import org.apache.iggy.message.Partitioning;
import org.apache.iggy.topic.CompressionAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("rawtypes")
public final class LapSegmentationJob {

    private static final Logger LOG = LoggerFactory.getLogger(LapSegmentationJob.class);
    private static final long DEFAULT_MESSAGE_EXPIRY_MICROS = 21_600_000_000L;
    private static final long DEFAULT_MAX_TOPIC_SIZE = 0L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LapSegmentationJob() {}

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.fromEnvironment();
        ensureIggyMetadata(settings);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(settings.checkpointIntervalMillis);

        IggyConnectionConfig connectionConfig = settings.toIggyConnectionConfig();

        DataStream<Map> gpsStream = env.fromSource(
                IggySource.<Map>builder()
                        .setConnectionConfig(connectionConfig)
                        .setStreamId(settings.iggyStream)
                        .setTopicId(settings.gpsInputTopic)
                        .setConsumerGroup(settings.gpsConsumerGroup)
                        .setDeserializer(new EnvelopeDeserializationSchema())
                        .setPollBatchSize(settings.sourcePollBatchSize)
                        .build(),
                org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(),
                "gps-source",
                TypeInformation.of(Map.class));

        DataStream<Map> imuStream = env.fromSource(
                IggySource.<Map>builder()
                        .setConnectionConfig(connectionConfig)
                        .setStreamId(settings.iggyStream)
                        .setTopicId(settings.imuInputTopic)
                        .setConsumerGroup(settings.imuConsumerGroup)
                        .setDeserializer(new EnvelopeDeserializationSchema())
                        .setPollBatchSize(settings.sourcePollBatchSize)
                        .build(),
                org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(),
                "imu-source",
                TypeInformation.of(Map.class));

        DataStream<Map> laps = gpsStream
                .keyBy(LapSegmentationJob::extractSessionId)
                .connect(imuStream.keyBy(LapSegmentationJob::extractSessionId))
                .process(new LapCoProcessFunction(settings.profilesJson))
                .name("lap-segmentation")
                .returns(TypeInformation.of(Map.class));

        laps.addSink(new TcpIggySink(settings)).name("lap-sink");

        env.execute("robo-services-lap-segmentation");
    }

    private static void ensureIggyMetadata(Settings settings) {
        IggyTcpClient client = IggyTcpClient.builder()
                .host(settings.tcpHost)
                .port(settings.tcpPort)
                .credentials(settings.iggyUsername, settings.iggyPassword)
                .connectionTimeout(Duration.ofSeconds(30))
                .requestTimeout(Duration.ofSeconds(30))
                .buildAndLogin();
        try {
            ensureTopic(client.topics(), settings, settings.outputTopic);
            ensureConsumerGroup(client.consumerGroups(), settings, settings.gpsInputTopic, settings.gpsConsumerGroup);
            ensureConsumerGroup(client.consumerGroups(), settings, settings.imuInputTopic, settings.imuConsumerGroup);
        } finally {
            client.close();
        }
    }

    private static void ensureTopic(TopicsClient topics, Settings settings, String topicName) {
        StreamId streamId = StreamId.of(settings.iggyStream);
        boolean exists = topics.getTopics(streamId).stream().anyMatch(t -> topicName.equals(t.name()));
        if (!exists) {
            topics.createTopic(
                    streamId,
                    1L,
                    CompressionAlgorithm.None,
                    BigInteger.valueOf(DEFAULT_MESSAGE_EXPIRY_MICROS),
                    BigInteger.valueOf(DEFAULT_MAX_TOPIC_SIZE),
                    Optional.empty(),
                    topicName);
            LOG.info("Created topic {} on stream {}", topicName, settings.iggyStream);
        }
    }

    private static void ensureConsumerGroup(
            ConsumerGroupsClient consumerGroups, Settings settings, String topicName, String groupName) {
        StreamId streamId = StreamId.of(settings.iggyStream);
        TopicId topicId = TopicId.of(topicName);
        boolean exists = consumerGroups.getConsumerGroups(streamId, topicId).stream()
                .anyMatch(g -> groupName.equals(g.name()));
        if (!exists) {
            consumerGroups.createConsumerGroup(streamId, topicId, groupName);
            LOG.info("Created consumer group {} on topic {}", groupName, topicName);
        }
    }

    static String extractSessionId(Map envelope) {
        String deviceId = asString(envelope.get("device_id"));
        String sourceSession = asString(envelope.get("source_session"));
        if (deviceId == null || sourceSession == null) return "unknown";
        return sourceSession + ":" + deviceId;
    }

    /**
     * Haversine distance between two lat/lon points in metres.
     */
    static double haversineMeters(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        final double R = 6_371_000.0;
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLat = Math.toRadians(lat2Deg - lat1Deg);
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Initial bearing from point 1 to point 2, in degrees [0, 360).
     */
    static double bearingDeg(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /**
     * Smallest angular difference between two bearings [0, 180].
     */
    static double bearingDelta(double a, double b) {
        double delta = Math.abs(a - b) % 360;
        return delta > 180 ? 360 - delta : delta;
    }

    static long parseTimestampMs(Map envelope) {
        String ts = asString(envelope.get("captured_at"));
        if (ts == null || ts.isBlank()) ts = asString(envelope.get("received_at"));
        if (ts == null || ts.isBlank()) return System.currentTimeMillis();
        try {
            return Instant.parse(ts).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    static Map<?, ?> extractPayload(Map envelope) {
        Object raw = envelope.get("payload");
        if (raw instanceof Map<?, ?> m) return m;

        // Iggy connector may deliver message as base64-wrapped envelope
        Object b64 = envelope.get("payload_b64");
        if (b64 instanceof String b64str && !envelope.containsKey("device_id")) {
            try {
                Map decoded = OBJECT_MAPPER.readValue(
                        java.util.Base64.getDecoder().decode(b64str), Map.class);
                return (Map<?, ?>) decoded.get("payload");
            } catch (IOException e) {
                LOG.warn("Failed to decode payload_b64: {}", e.getMessage());
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Device profiles
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class DeviceProfile implements Serializable {

        @JsonProperty("profile_id")
        String profileId = "scraps-v1";

        @JsonProperty("device_id_prefix")
        String deviceIdPrefix = "";

        @JsonProperty("geofence_radius_m")
        double geofenceRadiusM = 40.0;

        @JsonProperty("bearing_tolerance_deg")
        double bearingToleranceDeg = 35.0;

        @JsonProperty("min_lap_time_ms")
        long minLapTimeMs = 30_000L;

        @JsonProperty("launch_accel_threshold_ms2")
        double launchAccelThresholdMs2 = 2.5;

        @JsonProperty("launch_speed_floor_kph")
        double launchSpeedFloorKph = 3.0;

        @JsonProperty("imu_staleness_ms")
        long imuStalenessMs = 2_000L;

        @JsonProperty("imu_baseline_samples")
        int imuBaselineSamples = 10;

        @JsonProperty("gps")
        GpsConfig gps = new GpsConfig();

        @JsonProperty("imu")
        ImuConfig imu = null;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static final class GpsConfig implements Serializable {
            @JsonProperty("lat_field")
            String latField = "latitude";
            @JsonProperty("lon_field")
            String lonField = "longitude";
            @JsonProperty("speed_field")
            String speedField = "ground_speed_kph";
            @JsonProperty("heading_field")
            String headingField = "heading_deg";
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        static final class ImuConfig implements Serializable {
            @JsonProperty("accel_fields")
            List<String> accelFields = List.of("accel_m_s2.x", "accel_m_s2.y", "accel_m_s2.z");
            @JsonProperty("gravity_compensated")
            boolean gravityCompensated = false;
        }
    }

    static final class ProfileResolver implements Serializable {

        private final List<DeviceProfile> profiles;

        ProfileResolver(String profilesJson) {
            if (profilesJson == null || profilesJson.isBlank()) {
                profiles = List.of(new DeviceProfile());
            } else {
                try {
                    profiles = OBJECT_MAPPER.readValue(profilesJson, new TypeReference<>() {});
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to parse LAP_JOB_PROFILES_JSON: " + e.getMessage(), e);
                }
            }
        }

        DeviceProfile resolve(String deviceId) {
            if (deviceId != null) {
                String upper = deviceId.toUpperCase();
                for (DeviceProfile p : profiles) {
                    if (p.deviceIdPrefix != null && !p.deviceIdPrefix.isBlank()
                            && upper.startsWith(p.deviceIdPrefix.toUpperCase())) {
                        return p;
                    }
                }
            }
            return profiles.isEmpty() ? new DeviceProfile() : profiles.get(0);
        }
    }

    // -------------------------------------------------------------------------
    // Field extraction
    // -------------------------------------------------------------------------

    static final class SensorFieldExtractor {

        private SensorFieldExtractor() {}

        /**
         * Resolves a dot-path field (e.g. "accel_m_s2.x") from a Map hierarchy.
         * Returns null if any segment is missing or non-numeric at the leaf.
         */
        static Double extractDouble(Map<?, ?> map, String dotPath) {
            if (map == null || dotPath == null) return null;
            int dot = dotPath.indexOf('.');
            if (dot < 0) {
                Object v = map.get(dotPath);
                return v instanceof Number n ? n.doubleValue() : null;
            }
            Object nested = map.get(dotPath.substring(0, dot));
            if (nested instanceof Map<?, ?> nestedMap) {
                return extractDouble(nestedMap, dotPath.substring(dot + 1));
            }
            return null;
        }

        static double accelMagnitude(Map<?, ?> payload, List<String> fields) {
            double sumSq = 0;
            for (String f : fields) {
                Double v = extractDouble(payload, f);
                if (v == null) return Double.NaN;
                sumSq += v * v;
            }
            return Math.sqrt(sumSq);
        }
    }

    // -------------------------------------------------------------------------
    // Session state
    // -------------------------------------------------------------------------

    static final class SessionStateData implements Serializable {

        /** UNANCHORED | STAGED | LAPPING */
        String phase = "UNANCHORED";
        double anchorLat = 0;
        double anchorLon = 0;
        double anchorBearingDeg = Double.NaN;
        // IMU baseline calibration (STAGED phase, raw IMU only)
        double imuBaselineSum = 0;
        int imuBaselineCount = 0;
        // Most recent IMU reading from processElement2
        double lastImuMagnitude = Double.NaN;
        long lastImuTimestampMs = 0;
        // Current lap
        int lapNumber = 0;
        long lapStartMs = 0;
        double runningDistanceM = 0;
        double maxSpeedKph = 0;
        int gpsPointCount = 0;
        double prevLat = Double.NaN;
        double prevLon = Double.NaN;
        String resolvedProfileId = null;

        public SessionStateData() {}
    }

    // -------------------------------------------------------------------------
    // Core processing function
    // -------------------------------------------------------------------------

    static final class LapCoProcessFunction extends KeyedCoProcessFunction<String, Map, Map, Map> {

        private static final Logger LOG = LoggerFactory.getLogger(LapCoProcessFunction.class);

        private final ProfileResolver profileResolver;
        private transient ValueState<SessionStateData> sessionState;

        LapCoProcessFunction(String profilesJson) {
            this.profileResolver = new ProfileResolver(profilesJson);
        }

        @Override
        public void open(OpenContext ctx) {
            sessionState = getRuntimeContext().getState(
                    new ValueStateDescriptor<>("session", SessionStateData.class));
        }

        /** GPS message: telemetry fix or lap_anchor event. */
        @Override
        public void processElement1(Map envelope, Context ctx, org.apache.flink.util.Collector<Map> out)
                throws Exception {
            SessionStateData state = sessionState.value();
            if (state == null) state = new SessionStateData();

            Map<?, ?> payload = extractPayload(envelope);
            if (payload == null) {
                sessionState.update(state);
                return;
            }

            if (state.resolvedProfileId == null) {
                String deviceId = asString(envelope.get("device_id"));
                DeviceProfile resolved = profileResolver.resolve(deviceId);
                state.resolvedProfileId = resolved.profileId;
            }
            DeviceProfile profile = profileResolver.resolve(asString(envelope.get("device_id")));

            // Determine message type (firmware sets it on the envelope)
            String messageType = asString(envelope.get("message_type"));
            if (messageType == null) messageType = asString(payload.get("message_type"));

            long eventTimeMs = parseTimestampMs(envelope);

            if ("lap_anchor".equals(messageType)) {
                handleAnchorEvent(state, profile, payload, eventTimeMs, ctx.getCurrentKey());
                sessionState.update(state);
                return;
            }

            if ("UNANCHORED".equals(state.phase)) {
                sessionState.update(state);
                return;
            }

            Double lat = SensorFieldExtractor.extractDouble(payload, profile.gps.latField);
            Double lon = SensorFieldExtractor.extractDouble(payload, profile.gps.lonField);
            Double speedKph = SensorFieldExtractor.extractDouble(payload, profile.gps.speedField);

            if (lat == null || lon == null) {
                sessionState.update(state);
                return;
            }

            if ("STAGED".equals(state.phase)) {
                if (isLaunchDetected(state, profile, speedKph, eventTimeMs)) {
                    state.phase = "LAPPING";
                    state.lapNumber = 1;
                    state.lapStartMs = eventTimeMs;
                    state.runningDistanceM = 0;
                    state.maxSpeedKph = speedKph != null ? speedKph : 0;
                    state.gpsPointCount = 1;
                    state.prevLat = lat;
                    state.prevLon = lon;
                    // Bearing from anchor toward first fix after launch (direction filter baseline)
                    state.anchorBearingDeg = bearingDeg(state.anchorLat, state.anchorLon, lat, lon);
                    LOG.info("Launch detected for session {}, lap 1 started", ctx.getCurrentKey());
                }
                sessionState.update(state);
                return;
            }

            // LAPPING: accumulate metrics
            if (!Double.isNaN(state.prevLat)) {
                state.runningDistanceM += haversineMeters(state.prevLat, state.prevLon, lat, lon);
            }
            if (speedKph != null && speedKph > state.maxSpeedKph) {
                state.maxSpeedKph = speedKph;
            }
            state.gpsPointCount++;

            // Check geofence crossing — must happen BEFORE updating prevLat/prevLon
            // so that currentBearing reflects the approach vector (prevPos → curPos)
            double distToAnchor = haversineMeters(lat, lon, state.anchorLat, state.anchorLon);
            long elapsed = eventTimeMs - state.lapStartMs;
            if (distToAnchor <= profile.geofenceRadiusM) {
                double currentBearing = !Double.isNaN(state.prevLat)
                        ? bearingDeg(state.prevLat, state.prevLon, lat, lon) : 0;
                double bearingDiff = !Double.isNaN(state.anchorBearingDeg)
                        ? bearingDelta(currentBearing, state.anchorBearingDeg) : 0;
                LOG.info("Geofence hit: dist={:.1f}m elapsed={}ms bearing={:.1f}° anchorBearing={:.1f}° diff={:.1f}° session={}",
                        distToAnchor, elapsed, currentBearing, state.anchorBearingDeg, bearingDiff, ctx.getCurrentKey());
                if (elapsed >= profile.minLapTimeMs && bearingDiff <= profile.bearingToleranceDeg) {
                    emitLapRecord(envelope, state, eventTimeMs, speedKph, out);
                    // Start next lap
                    state.lapNumber++;
                    state.lapStartMs = eventTimeMs;
                    state.runningDistanceM = 0;
                    state.maxSpeedKph = speedKph != null ? speedKph : 0;
                    state.gpsPointCount = 0;
                }
            }

            state.prevLat = lat;
            state.prevLon = lon;

            sessionState.update(state);
        }

        /** IMU message: update acceleration state for launch detection. */
        @Override
        public void processElement2(Map envelope, Context ctx, org.apache.flink.util.Collector<Map> out)
                throws Exception {
            SessionStateData state = sessionState.value();
            if (state == null) {
                sessionState.update(new SessionStateData());
                return;
            }

            if ("UNANCHORED".equals(state.phase)) {
                sessionState.update(state);
                return;
            }

            DeviceProfile profile = profileResolver.resolve(asString(envelope.get("device_id")));
            if (profile.imu == null) {
                sessionState.update(state);
                return;
            }

            Map<?, ?> payload = extractPayload(envelope);
            if (payload == null) {
                sessionState.update(state);
                return;
            }

            long eventTimeMs = parseTimestampMs(envelope);
            double magnitude = SensorFieldExtractor.accelMagnitude(payload, profile.imu.accelFields);
            if (Double.isNaN(magnitude)) {
                sessionState.update(state);
                return;
            }

            // Build baseline during STAGED phase for raw (non-gravity-compensated) IMUs
            if ("STAGED".equals(state.phase)
                    && !profile.imu.gravityCompensated
                    && state.imuBaselineCount < profile.imuBaselineSamples) {
                state.imuBaselineSum += magnitude;
                state.imuBaselineCount++;
            }

            state.lastImuMagnitude = magnitude;
            state.lastImuTimestampMs = eventTimeMs;
            sessionState.update(state);
        }

        private void handleAnchorEvent(
                SessionStateData state, DeviceProfile profile, Map<?, ?> payload, long eventTimeMs, String key) {
            Double lat = SensorFieldExtractor.extractDouble(payload, profile.gps.latField);
            Double lon = SensorFieldExtractor.extractDouble(payload, profile.gps.lonField);
            if (lat == null || lon == null) {
                LOG.warn("lap_anchor event missing lat/lon for session {}", key);
                return;
            }
            state.phase = "STAGED";
            state.anchorLat = lat;
            state.anchorLon = lon;
            state.anchorBearingDeg = Double.NaN;
            state.imuBaselineSum = 0;
            state.imuBaselineCount = 0;
            state.lastImuMagnitude = Double.NaN;
            state.lapNumber = 0;
            state.runningDistanceM = 0;
            state.gpsPointCount = 0;
            LOG.info("Anchor set at ({}, {}) for session {}", lat, lon, key);
        }

        static boolean isLaunchDetected(
                SessionStateData state, DeviceProfile profile, Double speedKph, long eventTimeMs) {
            boolean speedOk = speedKph != null && speedKph >= profile.launchSpeedFloorKph;
            if (!speedOk) return false;

            if (profile.imu == null) {
                // No IMU configured: GPS speed floor is the only condition
                return true;
            }

            if (Double.isNaN(state.lastImuMagnitude)) return false;

            if (eventTimeMs - state.lastImuTimestampMs > profile.imuStalenessMs) return false;

            if (profile.imu.gravityCompensated) {
                return state.lastImuMagnitude >= profile.launchAccelThresholdMs2;
            } else {
                if (state.imuBaselineCount < profile.imuBaselineSamples) return false;
                double baseline = state.imuBaselineSum / state.imuBaselineCount;
                return Math.abs(state.lastImuMagnitude - baseline) >= profile.launchAccelThresholdMs2;
            }
        }

        @SuppressWarnings("unchecked")
        private void emitLapRecord(
                Map envelope,
                SessionStateData state,
                long lapEndMs,
                Double finalSpeedKph,
                org.apache.flink.util.Collector<Map> out) {
            long lapTimeMs = lapEndMs - state.lapStartMs;
            double avgSpeedKph = lapTimeMs > 0
                    ? (state.runningDistanceM / 1000.0) / (lapTimeMs / 3_600_000.0)
                    : 0;

            Map<String, Object> record = new HashMap<>();
            record.put("session_id", asString(envelope.get("session_id")));
            record.put("device_id", asString(envelope.get("device_id")));
            record.put("source_session", asString(envelope.get("source_session")));
            record.put("lap_number", state.lapNumber);
            record.put("lap_start_ms", state.lapStartMs);
            record.put("lap_end_ms", lapEndMs);
            record.put("lap_time_ms", lapTimeMs);
            record.put("anchor_lat", state.anchorLat);
            record.put("anchor_lon", state.anchorLon);
            record.put("max_speed_kph", state.maxSpeedKph);
            record.put("avg_speed_kph", Math.round(avgSpeedKph * 10.0) / 10.0);
            record.put("distance_m", Math.round(state.runningDistanceM * 10.0) / 10.0);
            record.put("gps_point_count", state.gpsPointCount);
            record.put("profile_id", state.resolvedProfileId);
            record.put("published_at", Instant.now().toString());

            out.collect(record);
            LOG.info("Lap {} complete for session {}: {}ms, {}m",
                    state.lapNumber, record.get("session_id"), lapTimeMs,
                    Math.round(state.runningDistanceM));
        }
    }

    // -------------------------------------------------------------------------
    // Iggy I/O
    // -------------------------------------------------------------------------

    static final class EnvelopeDeserializationSchema implements DeserializationSchema<Map> {

        @Override
        public Map deserialize(byte[] payload, RecordMetadata metadata) throws IOException {
            return OBJECT_MAPPER.readValue(payload, Map.class);
        }

        @Override
        public TypeDescriptor<Map> getProducedType() {
            return TypeDescriptor.of(Map.class);
        }
    }

    static final class TcpIggySink extends RichSinkFunction<Map> {

        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final String stream;
        private final String topic;
        private final Duration connectionTimeout;
        private final Duration requestTimeout;

        private transient IggyTcpClient client;

        TcpIggySink(Settings settings) {
            this.host = settings.tcpHost;
            this.port = settings.tcpPort;
            this.username = settings.iggyUsername;
            this.password = settings.iggyPassword;
            this.stream = settings.iggyStream;
            this.topic = settings.outputTopic;
            this.connectionTimeout = Duration.ofSeconds(30);
            this.requestTimeout = Duration.ofSeconds(30);
        }

        @Override
        public void open(OpenContext openContext) {
            client = IggyTcpClient.builder()
                    .host(host)
                    .port(port)
                    .credentials(username, password)
                    .connectionTimeout(connectionTimeout)
                    .requestTimeout(requestTimeout)
                    .buildAndLogin();
        }

        @Override
        public void invoke(Map value, Context context) throws IOException {
            String payload = OBJECT_MAPPER.writeValueAsString(value);
            client.messages().sendMessages(
                    StreamId.of(stream),
                    TopicId.of(topic),
                    Partitioning.balanced(),
                    List.of(Message.of(payload)));
        }

        @Override
        public void close() throws Exception {
            if (client != null) client.close();
        }
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    static final class Settings {

        final String iggyUsername;
        final String iggyPassword;
        final String tcpHost;
        final int tcpPort;
        final String iggyStream;
        final String gpsInputTopic;
        final String imuInputTopic;
        final String outputTopic;
        final String gpsConsumerGroup;
        final String imuConsumerGroup;
        final String profilesJson;
        final int sourcePollBatchSize;
        final long checkpointIntervalMillis;

        private Settings(
                String iggyUsername,
                String iggyPassword,
                String tcpHost,
                int tcpPort,
                String iggyStream,
                String gpsInputTopic,
                String imuInputTopic,
                String outputTopic,
                String gpsConsumerGroup,
                String imuConsumerGroup,
                String profilesJson,
                int sourcePollBatchSize,
                long checkpointIntervalMillis) {
            this.iggyUsername = iggyUsername;
            this.iggyPassword = iggyPassword;
            this.tcpHost = tcpHost;
            this.tcpPort = tcpPort;
            this.iggyStream = iggyStream;
            this.gpsInputTopic = gpsInputTopic;
            this.imuInputTopic = imuInputTopic;
            this.outputTopic = outputTopic;
            this.gpsConsumerGroup = gpsConsumerGroup;
            this.imuConsumerGroup = imuConsumerGroup;
            this.profilesJson = profilesJson;
            this.sourcePollBatchSize = sourcePollBatchSize;
            this.checkpointIntervalMillis = checkpointIntervalMillis;
        }

        static Settings fromEnvironment() {
            String connectionString = requireEnv("IGGY_CONNECTION_STRING");
            ConnectionDetails conn = ConnectionDetails.fromConnectionString(connectionString);
            return new Settings(
                    conn.username,
                    conn.password,
                    conn.tcpHost,
                    conn.tcpPort,
                    envOrDefault("IGGY_STREAM", "can-pub-sub-probe"),
                    envOrDefault("LAP_JOB_GPS_INPUT_TOPIC", "telemetry.raw.gps"),
                    envOrDefault("LAP_JOB_IMU_INPUT_TOPIC", "telemetry.raw.imu"),
                    envOrDefault("LAP_JOB_OUTPUT_TOPIC", "telemetry.derived.laps"),
                    envOrDefault("LAP_JOB_GPS_CONSUMER_GROUP", "lap-segmentation-gps"),
                    envOrDefault("LAP_JOB_IMU_CONSUMER_GROUP", "lap-segmentation-imu"),
                    envOrDefault("LAP_JOB_PROFILES_JSON", ""),
                    envInt("LAP_JOB_SOURCE_POLL_BATCH_SIZE", 100),
                    envLong("LAP_JOB_CHECKPOINT_INTERVAL_MS", 60_000L));
        }

        IggyConnectionConfig toIggyConnectionConfig() {
            return IggyConnectionConfig.builder()
                    .serverAddress(tcpHost + ":" + tcpPort)
                    .username(iggyUsername)
                    .password(iggyPassword)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .build();
        }

        private static String requireEnv(String key) {
            String v = System.getenv(key);
            if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " must be set");
            return v;
        }

        private static String envOrDefault(String key, String def) {
            String v = System.getenv(key);
            return (v == null || v.isBlank()) ? def : v;
        }

        private static int envInt(String key, int def) {
            String v = System.getenv(key);
            return (v == null || v.isBlank()) ? def : Integer.parseInt(v);
        }

        private static long envLong(String key, long def) {
            String v = System.getenv(key);
            return (v == null || v.isBlank()) ? def : Long.parseLong(v);
        }
    }

    static final class ConnectionDetails {

        final String username;
        final String password;
        final String tcpHost;
        final int tcpPort;

        private ConnectionDetails(String username, String password, String tcpHost, int tcpPort) {
            this.username = username;
            this.password = password;
            this.tcpHost = tcpHost;
            this.tcpPort = tcpPort;
        }

        static ConnectionDetails fromConnectionString(String connectionString) {
            java.net.URI uri = java.net.URI.create(connectionString);
            String userInfo = uri.getUserInfo();
            if (userInfo == null || !userInfo.contains(":")) {
                throw new IllegalArgumentException("IGGY_CONNECTION_STRING must contain username:password");
            }
            String[] parts = userInfo.split(":", 2);
            int tcpPort = uri.getPort() > 0 ? uri.getPort() : 8090;
            return new ConnectionDetails(parts[0], parts[1], uri.getHost(), tcpPort);
        }
    }
}
