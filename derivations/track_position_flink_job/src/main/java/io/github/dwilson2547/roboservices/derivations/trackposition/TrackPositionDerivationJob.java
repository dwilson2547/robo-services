package io.github.dwilson2547.roboservices.derivations.trackposition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;
import org.apache.flink.util.Collector;
import org.apache.iggy.client.blocking.ConsumerGroupsClient;
import org.apache.iggy.client.blocking.MessagesClient;
import org.apache.iggy.client.blocking.TopicsClient;
import org.apache.iggy.client.blocking.tcp.IggyTcpClient;
import org.apache.iggy.connector.config.IggyConnectionConfig;
import org.apache.iggy.connector.flink.source.IggySource;
import org.apache.iggy.connector.serialization.DeserializationSchema;
import org.apache.iggy.connector.serialization.RecordMetadata;
import org.apache.iggy.connector.serialization.TypeDescriptor;
import org.apache.iggy.identifier.StreamId;
import org.apache.iggy.identifier.TopicId;
import org.apache.iggy.message.Message;
import org.apache.iggy.message.Partitioning;
import org.apache.iggy.topic.CompressionAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class TrackPositionDerivationJob {

    private static final Logger LOG = LoggerFactory.getLogger(TrackPositionDerivationJob.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DERIVATION_NAME = "track_position";
    private static final long DEFAULT_MESSAGE_EXPIRY_MICROS = 21_600_000_000L;
    private static final long DEFAULT_MAX_TOPIC_SIZE = 0L;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    private TrackPositionDerivationJob() {}

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.fromEnvironment();
        ensureIggyMetadata(settings);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(settings.checkpointIntervalMillis);

        IggyConnectionConfig connectionConfig = settings.toIggyConnectionConfig();

        DataStream<Map> input = env.fromSource(
                IggySource.<Map>builder()
                        .setConnectionConfig(connectionConfig)
                        .setStreamId(settings.iggyStream)
                        .setTopicId(settings.inputTopic)
                        .setConsumerGroup(settings.consumerGroup)
                        .setDeserializer(new EnvelopeDeserializationSchema())
                        .setPollBatchSize(settings.sourcePollBatchSize)
                        .build(),
                org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(),
                "gps-source",
                TypeInformation.of(Map.class));

        DataStream<Map> derived = input
                .flatMap(new TrackPositionExtractor(settings))
                .name("derive-track-position")
                .returns(TypeInformation.of(Map.class));

        derived.addSink(new TcpIggySink(settings)).name("track-position-sink");

        env.execute("robo-services-track-position");
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
            ensureConsumerGroup(client.consumerGroups(), settings, settings.inputTopic, settings.consumerGroup);
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
                    java.math.BigInteger.valueOf(DEFAULT_MESSAGE_EXPIRY_MICROS),
                    java.math.BigInteger.valueOf(DEFAULT_MAX_TOPIC_SIZE),
                    java.util.Optional.empty(),
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

    static GpsSample requireGpsSample(Map envelope) {
        if (envelope.containsKey("payload_b64") && !envelope.containsKey("device_id")) {
            try {
                Map decoded = OBJECT_MAPPER.readValue(
                        Base64.getDecoder().decode(asString(envelope.get("payload_b64"))), Map.class);
                return requireGpsSample(decoded);
            } catch (IOException exception) {
                throw new IllegalArgumentException("payload_b64 could not be decoded", exception);
            }
        }

        String deviceId = requireText(asString(envelope.get("device_id")), "device_id");
        String sourceSession = requireText(asString(envelope.get("source_session")), "source_session");
        String timestamp = asString(envelope.get("captured_at"));
        if (timestamp == null || timestamp.isBlank()) {
            timestamp = asString(envelope.get("received_at"));
        }
        if (timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException("captured_at or received_at must be present");
        }
        Object payloadObject = envelope.get("payload");
        if (!(payloadObject instanceof Map payload)) {
            throw new IllegalArgumentException("payload must be present");
        }
        Object latitudeObject = payload.get("latitude");
        Object longitudeObject = payload.get("longitude");
        if (!(latitudeObject instanceof Number latitude)) {
            throw new IllegalArgumentException("payload.latitude must be present");
        }
        if (!(longitudeObject instanceof Number longitude)) {
            throw new IllegalArgumentException("payload.longitude must be present");
        }
        double speedKph = payload.get("ground_speed_kph") instanceof Number speed ? speed.doubleValue() : Double.NaN;
        double headingDeg = payload.get("heading_deg") instanceof Number heading ? heading.doubleValue() : Double.NaN;
        return new GpsSample(
                deviceId,
                sourceSession,
                Instant.parse(timestamp),
                latitude.doubleValue(),
                longitude.doubleValue(),
                speedKph,
                headingDeg);
    }

    static Map<String, Object> buildDerivedTrackPositionRecord(
            GpsSample sample,
            TrackDefinition track,
            TrackMatch match,
            String inputTopic,
            String outputTopic) {
        Map<String, Object> record = new HashMap<>();
        record.put("device_id", sample.deviceId);
        record.put("source_session", sample.sourceSession);
        record.put("captured_at", sample.timestamp.toString());
        record.put("latitude", sample.latitude);
        record.put("longitude", sample.longitude);
        if (!Double.isNaN(sample.groundSpeedKph)) {
            record.put("ground_speed_kph", sample.groundSpeedKph);
        }
        if (!Double.isNaN(sample.headingDeg)) {
            record.put("heading_deg", sample.headingDeg);
        }
        record.put("track_id", track.id);
        record.put("track_name", track.name);
        record.put("s_m", round3(match.sMeters));
        record.put("progress_pct", round3((match.sMeters / track.totalLengthMeters) * 100.0));
        record.put("distance_to_track_m", round3(match.distanceMeters));
        record.put("snapped_latitude", match.snappedLatitude);
        record.put("snapped_longitude", match.snappedLongitude);
        record.put("track_length_m", round3(track.totalLengthMeters));
        record.put("source_topic", inputTopic);
        record.put("topic", outputTopic);
        record.put("derivation", DERIVATION_NAME);
        return record;
    }

    static TrackMatch findBestMatch(GpsSample sample, List<TrackDefinition> tracks, double maxMatchDistanceMeters) {
        TrackMatch bestMatch = null;
        for (TrackDefinition track : tracks) {
            TrackMatch candidate = matchTrack(track, sample.latitude, sample.longitude);
            if (candidate == null || candidate.distanceMeters > maxMatchDistanceMeters) {
                continue;
            }
            if (bestMatch == null || candidate.distanceMeters < bestMatch.distanceMeters) {
                bestMatch = candidate.withTrack(track);
            }
        }
        return bestMatch;
    }

    static TrackMatch matchTrack(TrackDefinition track, double latitude, double longitude) {
        if (track.points.size() < 2) {
            return null;
        }
        TrackMatch best = null;
        double accumulated = 0.0;
        for (int i = 0; i < track.points.size() - 1; i++) {
            GeoPoint start = track.points.get(i);
            GeoPoint end = track.points.get(i + 1);
            SegmentProjection projection = projectOntoSegment(latitude, longitude, start, end);
            double sMeters = accumulated + projection.alongSegmentMeters;
            TrackMatch candidate = new TrackMatch(
                    null,
                    sMeters,
                    projection.distanceMeters,
                    projection.snappedLatitude,
                    projection.snappedLongitude);
            if (best == null || candidate.distanceMeters < best.distanceMeters) {
                best = candidate;
            }
            accumulated += haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude);
        }
        return best;
    }

    static List<TrackDefinition> parseTrackDefinitions(String tracksJson) throws IOException {
        JsonNode tracks = OBJECT_MAPPER.readTree(tracksJson);
        List<TrackDefinition> definitions = new ArrayList<>();
        if (!tracks.isArray()) {
            return definitions;
        }
        for (JsonNode trackNode : tracks) {
            JsonNode geometry = trackNode.get("geometry");
            List<GeoPoint> points = extractLinePoints(geometry);
            if (points.size() < 2) {
                continue;
            }
            int id = trackNode.path("id").asInt();
            String name = trackNode.path("name").asText("track-" + id);
            definitions.add(new TrackDefinition(id, name, points, computeTotalLengthMeters(points)));
        }
        return definitions;
    }

    private static List<GeoPoint> extractLinePoints(JsonNode geometryNode) {
        if (geometryNode == null || geometryNode.isNull()) {
            return List.of();
        }
        String type = geometryNode.path("type").asText();
        return switch (type) {
            case "Feature" -> extractLinePoints(geometryNode.get("geometry"));
            case "FeatureCollection" -> extractFeatureCollectionPoints(geometryNode.get("features"));
            case "LineString" -> coordinatesToPoints(geometryNode.get("coordinates"));
            case "MultiLineString" -> multiLineCoordinatesToPoints(geometryNode.get("coordinates"));
            default -> List.of();
        };
    }

    private static List<GeoPoint> extractFeatureCollectionPoints(JsonNode featuresNode) {
        if (featuresNode == null || !featuresNode.isArray()) {
            return List.of();
        }
        for (JsonNode feature : featuresNode) {
            List<GeoPoint> points = extractLinePoints(feature);
            if (points.size() >= 2) {
                return points;
            }
        }
        return List.of();
    }

    private static List<GeoPoint> coordinatesToPoints(JsonNode coordinatesNode) {
        List<GeoPoint> points = new ArrayList<>();
        if (coordinatesNode == null || !coordinatesNode.isArray()) {
            return points;
        }
        for (JsonNode coordinate : coordinatesNode) {
            if (coordinate.isArray() && coordinate.size() >= 2) {
                points.add(new GeoPoint(coordinate.get(1).asDouble(), coordinate.get(0).asDouble()));
            }
        }
        return points;
    }

    private static List<GeoPoint> multiLineCoordinatesToPoints(JsonNode coordinatesNode) {
        List<GeoPoint> points = new ArrayList<>();
        if (coordinatesNode == null || !coordinatesNode.isArray()) {
            return points;
        }
        boolean firstLine = true;
        for (JsonNode lineCoordinates : coordinatesNode) {
            List<GeoPoint> line = coordinatesToPoints(lineCoordinates);
            if (line.isEmpty()) {
                continue;
            }
            if (!firstLine && !points.isEmpty() && !points.get(points.size() - 1).equals(line.get(0))) {
                points.add(line.get(0));
            }
            points.addAll(firstLine ? line : line.subList(1, line.size()));
            firstLine = false;
        }
        return points;
    }

    private static double computeTotalLengthMeters(List<GeoPoint> points) {
        double total = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            GeoPoint start = points.get(i);
            GeoPoint end = points.get(i + 1);
            total += haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude);
        }
        return total;
    }

    private static SegmentProjection projectOntoSegment(double latitude, double longitude, GeoPoint start, GeoPoint end) {
        double meanLatRad = Math.toRadians((start.latitude + end.latitude + latitude) / 3.0);
        double metersPerLon = METERS_PER_DEGREE_LAT * Math.cos(meanLatRad);

        double bx = (end.longitude - start.longitude) * metersPerLon;
        double by = (end.latitude - start.latitude) * METERS_PER_DEGREE_LAT;
        double px = (longitude - start.longitude) * metersPerLon;
        double py = (latitude - start.latitude) * METERS_PER_DEGREE_LAT;

        double segmentLengthSquared = bx * bx + by * by;
        if (segmentLengthSquared == 0.0) {
            return new SegmentProjection(
                    Math.hypot(px, py),
                    0.0,
                    start.latitude,
                    start.longitude);
        }

        double t = ((px * bx) + (py * by)) / segmentLengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));

        double projectedX = t * bx;
        double projectedY = t * by;
        double dx = px - projectedX;
        double dy = py - projectedY;

        return new SegmentProjection(
                Math.hypot(dx, dy),
                Math.hypot(projectedX, projectedY),
                start.latitude + ((end.latitude - start.latitude) * t),
                start.longitude + ((end.longitude - start.longitude) * t));
    }

    static double haversineMeters(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLat = Math.toRadians(lat2Deg - lat1Deg);
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String asString(Object value) {
        return value instanceof String stringValue ? stringValue : null;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be present");
        }
        return value;
    }

    static final class TrackPositionExtractor extends RichFlatMapFunction<Map, Map> {

        private final Settings settings;

        private transient HttpClient httpClient;
        private transient List<TrackDefinition> cachedTracks;
        private transient Instant cacheExpiresAt;

        TrackPositionExtractor(Settings settings) {
            this.settings = settings;
        }

        @Override
        public void open(org.apache.flink.api.common.functions.OpenContext openContext) {
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            cachedTracks = List.of();
            cacheExpiresAt = Instant.EPOCH;
        }

        @Override
        public void flatMap(Map value, Collector<Map> out) {
            try {
                GpsSample sample = requireGpsSample(value);
                List<TrackDefinition> tracks = ensureTracks();
                TrackMatch match = findBestMatch(sample, tracks, settings.maxMatchDistanceMeters);
                if (match == null || match.track == null) {
                    return;
                }
                out.collect(buildDerivedTrackPositionRecord(
                        sample,
                        match.track,
                        match,
                        settings.inputTopic,
                        settings.outputTopic));
            } catch (RuntimeException exc) {
                LOG.warn("Skipping gps message for track-position derivation: {}", exc.getMessage());
            }
        }

        private List<TrackDefinition> ensureTracks() {
            Instant now = Instant.now();
            if (now.isBefore(cacheExpiresAt) && !cachedTracks.isEmpty()) {
                return cachedTracks;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(settings.registryTracksUri)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("registry returned status " + response.statusCode());
                }
                cachedTracks = parseTrackDefinitions(response.body());
                cacheExpiresAt = now.plusSeconds(settings.trackCacheTtlSeconds);
                LOG.info("Loaded {} line-based track geometries from registry", cachedTracks.size());
                return cachedTracks;
            } catch (IOException | InterruptedException exc) {
                throw new IllegalStateException("failed to refresh registry tracks", exc);
            }
        }
    }

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

        private transient IggyTcpClient client;
        private transient MessagesClient messagesClient;

        TcpIggySink(Settings settings) {
            this.host = settings.tcpHost;
            this.port = settings.tcpPort;
            this.username = settings.iggyUsername;
            this.password = settings.iggyPassword;
            this.stream = settings.iggyStream;
            this.topic = settings.outputTopic;
        }

        @Override
        public void open(org.apache.flink.api.common.functions.OpenContext openContext) {
            client = IggyTcpClient.builder()
                    .host(host)
                    .port(port)
                    .credentials(username, password)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .requestTimeout(Duration.ofSeconds(30))
                    .buildAndLogin();
            messagesClient = client.messages();
        }

        @Override
        public void invoke(Map value, Context context) throws IOException {
            String payload = OBJECT_MAPPER.writeValueAsString(value);
            messagesClient.sendMessages(
                    StreamId.of(stream),
                    TopicId.of(topic),
                    Partitioning.balanced(),
                    List.of(Message.of(payload)));
        }

        @Override
        public void close() throws Exception {
            if (client != null) {
                client.close();
            }
        }
    }

    static final class Settings implements Serializable {

        private final String iggyUsername;
        private final String iggyPassword;
        private final String tcpHost;
        private final int tcpPort;
        private final String iggyStream;
        private final String inputTopic;
        private final String outputTopic;
        private final String consumerGroup;
        private final int sourcePollBatchSize;
        private final long checkpointIntervalMillis;
        private final URI registryTracksUri;
        private final long trackCacheTtlSeconds;
        private final double maxMatchDistanceMeters;

        private Settings(
                String iggyUsername,
                String iggyPassword,
                String tcpHost,
                int tcpPort,
                String iggyStream,
                String inputTopic,
                String outputTopic,
                String consumerGroup,
                int sourcePollBatchSize,
                long checkpointIntervalMillis,
                URI registryTracksUri,
                long trackCacheTtlSeconds,
                double maxMatchDistanceMeters) {
            this.iggyUsername = iggyUsername;
            this.iggyPassword = iggyPassword;
            this.tcpHost = tcpHost;
            this.tcpPort = tcpPort;
            this.iggyStream = iggyStream;
            this.inputTopic = inputTopic;
            this.outputTopic = outputTopic;
            this.consumerGroup = consumerGroup;
            this.sourcePollBatchSize = sourcePollBatchSize;
            this.checkpointIntervalMillis = checkpointIntervalMillis;
            this.registryTracksUri = registryTracksUri;
            this.trackCacheTtlSeconds = trackCacheTtlSeconds;
            this.maxMatchDistanceMeters = maxMatchDistanceMeters;
        }

        static Settings fromEnvironment() {
            String iggyConnectionString = requireEnv("IGGY_CONNECTION_STRING");
            ConnectionDetails connectionDetails = ConnectionDetails.fromConnectionString(iggyConnectionString);
            String registryUrl = envOrDefault("TRACK_POSITION_JOB_REGISTRY_URL",
                    "http://robo-registry.robo-services.svc.cluster.local");
            try {
                return new Settings(
                        connectionDetails.username,
                        connectionDetails.password,
                        connectionDetails.tcpHost,
                        connectionDetails.tcpPort,
                        envOrDefault("IGGY_STREAM", "can-pub-sub-probe"),
                        envOrDefault("TRACK_POSITION_JOB_INPUT_TOPIC", "telemetry.raw.gps"),
                        envOrDefault("TRACK_POSITION_JOB_OUTPUT_TOPIC", "telemetry.derived.track_position"),
                        envOrDefault("TRACK_POSITION_JOB_CONSUMER_GROUP", "track-position"),
                        envInt("TRACK_POSITION_JOB_SOURCE_POLL_BATCH_SIZE", 100),
                        envLong("TRACK_POSITION_JOB_CHECKPOINT_INTERVAL_MS", 60000L),
                        new URI(registryUrl + "/api/tracks"),
                        envLong("TRACK_POSITION_JOB_TRACK_CACHE_TTL_S", 300L),
                        envDouble("TRACK_POSITION_JOB_MAX_MATCH_DISTANCE_METERS", 75.0));
            } catch (URISyntaxException exc) {
                throw new IllegalArgumentException("TRACK_POSITION_JOB_REGISTRY_URL is invalid", exc);
            }
        }

        IggyConnectionConfig toIggyConnectionConfig() {
            String serverAddress = tcpHost + ":" + tcpPort;
            return IggyConnectionConfig.builder()
                    .serverAddress(serverAddress)
                    .username(iggyUsername)
                    .password(iggyPassword)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .build();
        }

        private static String requireEnv(String key) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(key + " must be set");
            }
            return value;
        }

        private static String envOrDefault(String key, String defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : value;
        }

        private static int envInt(String key, int defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        }

        private static long envLong(String key, long defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
        }

        private static double envDouble(String key, double defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value);
        }
    }

    static final class ConnectionDetails {
        final String username;
        final String password;
        final String tcpHost;
        final int tcpPort;

        ConnectionDetails(String username, String password, String tcpHost, int tcpPort) {
            this.username = username;
            this.password = password;
            this.tcpHost = tcpHost;
            this.tcpPort = tcpPort;
        }

        static ConnectionDetails fromConnectionString(String connectionString) {
            String trimmed = requireText(connectionString, "IGGY_CONNECTION_STRING");
            if (!trimmed.startsWith("iggy+tcp://")) {
                throw new IllegalArgumentException("IGGY_CONNECTION_STRING must start with iggy+tcp://");
            }
            URI uri = URI.create(trimmed.replace("iggy+tcp://", "tcp://"));
            String userInfo = requireText(uri.getUserInfo(), "IGGY_CONNECTION_STRING user info");
            int colonIndex = userInfo.indexOf(':');
            if (colonIndex <= 0 || colonIndex == userInfo.length() - 1) {
                throw new IllegalArgumentException("IGGY_CONNECTION_STRING must include username and password");
            }
            String host = requireText(uri.getHost(), "IGGY_CONNECTION_STRING host");
            int port = uri.getPort();
            if (port <= 0) {
                throw new IllegalArgumentException("IGGY_CONNECTION_STRING must include a tcp port");
            }
            return new ConnectionDetails(
                    userInfo.substring(0, colonIndex),
                    userInfo.substring(colonIndex + 1),
                    host,
                    port);
        }
    }

    static final class GpsSample implements Serializable {
        final String deviceId;
        final String sourceSession;
        final Instant timestamp;
        final double latitude;
        final double longitude;
        final double groundSpeedKph;
        final double headingDeg;

        GpsSample(
                String deviceId,
                String sourceSession,
                Instant timestamp,
                double latitude,
                double longitude,
                double groundSpeedKph,
                double headingDeg) {
            this.deviceId = deviceId;
            this.sourceSession = sourceSession;
            this.timestamp = timestamp;
            this.latitude = latitude;
            this.longitude = longitude;
            this.groundSpeedKph = groundSpeedKph;
            this.headingDeg = headingDeg;
        }
    }

    static final class GeoPoint implements Serializable {
        final double latitude;
        final double longitude;

        GeoPoint(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof GeoPoint point)) return false;
            return Double.compare(latitude, point.latitude) == 0
                    && Double.compare(longitude, point.longitude) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(latitude, longitude);
        }
    }

    static final class TrackDefinition implements Serializable {
        final int id;
        final String name;
        final List<GeoPoint> points;
        final double totalLengthMeters;

        TrackDefinition(int id, String name, List<GeoPoint> points, double totalLengthMeters) {
            this.id = id;
            this.name = name;
            this.points = points;
            this.totalLengthMeters = totalLengthMeters;
        }
    }

    static final class SegmentProjection implements Serializable {
        final double distanceMeters;
        final double alongSegmentMeters;
        final double snappedLatitude;
        final double snappedLongitude;

        SegmentProjection(
                double distanceMeters,
                double alongSegmentMeters,
                double snappedLatitude,
                double snappedLongitude) {
            this.distanceMeters = distanceMeters;
            this.alongSegmentMeters = alongSegmentMeters;
            this.snappedLatitude = snappedLatitude;
            this.snappedLongitude = snappedLongitude;
        }
    }

    static final class TrackMatch implements Serializable {
        final TrackDefinition track;
        final double sMeters;
        final double distanceMeters;
        final double snappedLatitude;
        final double snappedLongitude;

        TrackMatch(
                TrackDefinition track,
                double sMeters,
                double distanceMeters,
                double snappedLatitude,
                double snappedLongitude) {
            this.track = track;
            this.sMeters = sMeters;
            this.distanceMeters = distanceMeters;
            this.snappedLatitude = snappedLatitude;
            this.snappedLongitude = snappedLongitude;
        }

        TrackMatch withTrack(TrackDefinition value) {
            return new TrackMatch(value, sMeters, distanceMeters, snappedLatitude, snappedLongitude);
        }
    }
}
