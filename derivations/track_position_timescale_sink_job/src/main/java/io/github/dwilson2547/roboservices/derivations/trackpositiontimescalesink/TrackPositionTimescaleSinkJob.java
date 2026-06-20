package io.github.dwilson2547.roboservices.derivations.trackpositiontimescalesink;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.iggy.connector.config.IggyConnectionConfig;
import org.apache.iggy.connector.flink.source.IggySource;
import org.apache.iggy.connector.serialization.DeserializationSchema;
import org.apache.iggy.connector.serialization.RecordMetadata;
import org.apache.iggy.connector.serialization.TypeDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TrackPositionTimescaleSinkJob {

    private static final Logger LOG = LoggerFactory.getLogger(TrackPositionTimescaleSinkJob.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TrackPositionTimescaleSinkJob() {
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.fromEnvironment();
        ensureIggyConsumerGroup(settings);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(settings.checkpointIntervalMillis);

        DataStream<Map> input = env.fromSource(
                IggySource.<Map>builder()
                        .setConnectionConfig(settings.toIggyConnectionConfig())
                        .setStreamId(settings.iggyStream)
                        .setTopicId(settings.inputTopic)
                        .setConsumerGroup(settings.consumerGroup)
                        .setDeserializer(new EnvelopeDeserializationSchema())
                        .setPollBatchSize(settings.sourcePollBatchSize)
                        .build(),
                org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(),
                "track-position-source",
                TypeInformation.of(Map.class));

        input.flatMap(new TrackPositionSampleExtractor())
                .name("extract-track-position-samples")
                .returns(TypeInformation.of(TrackPositionSample.class))
                .addSink(new TimescaleSink(settings))
                .name("timescaledb-sink");

        env.execute("robo-services-track-position-timescale-sink");
    }

    private static void ensureIggyConsumerGroup(Settings settings) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        String accessToken = login(settings, client);
        HttpRequest listRequest = HttpRequest.newBuilder(settings.consumerGroupPath())
                .GET()
                .build();
        HttpResponse<String> listResponse = send(settings, client, listRequest, accessToken);
        List<ConsumerGroupMetadata> groups =
                OBJECT_MAPPER.readValue(listResponse.body(), new TypeReference<List<ConsumerGroupMetadata>>() {});
        boolean exists = groups.stream().anyMatch(group -> settings.consumerGroup.equals(group.getName()));
        if (exists) {
            return;
        }

        HttpRequest createRequest = HttpRequest.newBuilder(settings.consumerGroupPath())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        OBJECT_MAPPER.writeValueAsString(Map.of("name", settings.consumerGroup))))
                .build();
        HttpResponse<String> createResponse = send(settings, client, createRequest, accessToken);
        if (createResponse.statusCode() != 201) {
            throw new IllegalStateException("Failed to create consumer group: " + createResponse.body());
        }
        LOG.info(
                "Created consumer group {} for stream {} topic {}",
                settings.consumerGroup,
                settings.iggyStream,
                settings.inputTopic);
    }

    private static String login(Settings settings, HttpClient client) throws IOException, InterruptedException {
        HttpRequest loginRequest = HttpRequest.newBuilder(settings.httpApiUrl.resolve("/users/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        OBJECT_MAPPER.writeValueAsString(Map.of(
                                "username", settings.iggyUsername,
                                "password", settings.iggyPassword))))
                .build();
        HttpResponse<String> response = client.send(loginRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to login to Iggy HTTP API: " + response.body());
        }
        JsonNode node = OBJECT_MAPPER.readTree(response.body());
        JsonNode tokenNode = node.path("access_token").path("token");
        if (tokenNode.isMissingNode() || tokenNode.asText().isBlank()) {
            throw new IllegalStateException("Iggy HTTP API login response did not contain an access token");
        }
        return tokenNode.asText();
    }

    private static HttpResponse<String> send(
            Settings settings, HttpClient client, HttpRequest request, String accessToken)
            throws IOException, InterruptedException {
        HttpRequest authorizedRequest = HttpRequest.newBuilder(request.uri())
                .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .build();
        return client.send(authorizedRequest, HttpResponse.BodyHandlers.ofString());
    }

    static TrackPositionSample requireTrackPositionSample(Map envelope) {
        Object payloadBase64 = envelope.get("payload_b64");
        if (payloadBase64 instanceof String payloadBase64String && !envelope.containsKey("track_id")) {
            try {
                Map decoded = OBJECT_MAPPER.readValue(Base64.getDecoder().decode(payloadBase64String), Map.class);
                return requireTrackPositionSample(decoded);
            } catch (IOException exception) {
                throw new IllegalArgumentException("payload_b64 could not be decoded", exception);
            }
        }

        String deviceId = requireText(asString(envelope.get("device_id")), "device_id");
        String sourceSession = requireText(asString(envelope.get("source_session")), "source_session");
        String capturedAt = requireText(asString(envelope.get("captured_at")), "captured_at");
        Integer trackId = requireInteger(envelope.get("track_id"), "track_id");
        String trackName = requireText(asString(envelope.get("track_name")), "track_name");

        return new TrackPositionSample(
                deviceId,
                sourceSession,
                Instant.parse(capturedAt),
                trackId,
                trackName,
                requireDouble(envelope.get("s_m"), "s_m"),
                optionalDouble(envelope.get("progress_pct")),
                optionalDouble(envelope.get("distance_to_track_m")),
                optionalDouble(envelope.get("ground_speed_kph")),
                optionalDouble(envelope.get("latitude")),
                optionalDouble(envelope.get("longitude")),
                optionalDouble(envelope.get("snapped_latitude")),
                optionalDouble(envelope.get("snapped_longitude")),
                optionalDouble(envelope.get("heading_deg")),
                asString(envelope.get("topic")),
                asString(envelope.get("source_topic")),
                asString(envelope.get("derivation")),
                envelope);
    }

    static String qualifiedTableName(String schemaName, String tableName) {
        return quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
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

    private static Integer requireInteger(Object value, String fieldName) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException(fieldName + " must be present");
    }

    private static double requireDouble(Object value, String fieldName) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException(fieldName + " must be present");
    }

    private static Double optionalDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
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

    static final class TrackPositionSampleExtractor implements FlatMapFunction<Map, TrackPositionSample> {

        private static final Logger LOG = LoggerFactory.getLogger(TrackPositionSampleExtractor.class);

        @Override
        public void flatMap(Map value, Collector<TrackPositionSample> out) {
            try {
                out.collect(requireTrackPositionSample(value));
            } catch (RuntimeException exc) {
                LOG.warn("Skipping malformed track-position message: {}", exc.getMessage());
            }
        }
    }

    static final class TimescaleSink extends RichSinkFunction<TrackPositionSample> {

        private final Settings settings;
        private transient Connection connection;
        private transient PreparedStatement upsertStatement;

        TimescaleSink(Settings settings) {
            this.settings = settings;
        }

        @Override
        public void open(org.apache.flink.api.common.functions.OpenContext openContext) throws Exception {
            Class.forName("org.postgresql.Driver");
            connection = DriverManager.getConnection(settings.jdbcUrl(), settings.dbUser, settings.dbPassword);
            connection.setAutoCommit(true);
            ensureSchema(connection, settings);
            upsertStatement = connection.prepareStatement(buildUpsertSql(settings));
        }

        @Override
        public void invoke(TrackPositionSample value, Context context) throws Exception {
            upsertStatement.setTimestamp(1, Timestamp.from(value.capturedAt));
            upsertStatement.setString(2, value.deviceId);
            upsertStatement.setString(3, value.sourceSession);
            upsertStatement.setInt(4, value.trackId);
            upsertStatement.setString(5, value.trackName);
            upsertStatement.setDouble(6, value.sMeters);
            setNullableDouble(upsertStatement, 7, value.progressPct);
            setNullableDouble(upsertStatement, 8, value.distanceToTrackMeters);
            setNullableDouble(upsertStatement, 9, value.groundSpeedKph);
            setNullableDouble(upsertStatement, 10, value.latitude);
            setNullableDouble(upsertStatement, 11, value.longitude);
            setNullableDouble(upsertStatement, 12, value.snappedLatitude);
            setNullableDouble(upsertStatement, 13, value.snappedLongitude);
            setNullableDouble(upsertStatement, 14, value.headingDeg);
            upsertStatement.setString(15, value.topic);
            upsertStatement.setString(16, value.sourceTopic);
            upsertStatement.setString(17, value.derivation);
            upsertStatement.setString(18, OBJECT_MAPPER.writeValueAsString(value.rawRecord));
            upsertStatement.executeUpdate();
        }

        @Override
        public void close() throws Exception {
            if (upsertStatement != null) {
                upsertStatement.close();
            }
            if (connection != null) {
                connection.close();
            }
        }

        private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws SQLException {
            if (value == null) {
                statement.setNull(index, java.sql.Types.DOUBLE);
                return;
            }
            statement.setDouble(index, value);
        }

        private static String buildUpsertSql(Settings settings) {
            String tableName = qualifiedTableName(settings.schemaName, settings.tableName);
            return "INSERT INTO " + tableName + " ("
                    + "captured_at, device_id, source_session, track_id, track_name, s_m, progress_pct, "
                    + "distance_to_track_m, ground_speed_kph, latitude, longitude, snapped_latitude, "
                    + "snapped_longitude, heading_deg, topic, source_topic, derivation, raw_record"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
                    + "ON CONFLICT (device_id, source_session, captured_at, track_id) DO UPDATE SET "
                    + "track_name = EXCLUDED.track_name, "
                    + "s_m = EXCLUDED.s_m, "
                    + "progress_pct = EXCLUDED.progress_pct, "
                    + "distance_to_track_m = EXCLUDED.distance_to_track_m, "
                    + "ground_speed_kph = EXCLUDED.ground_speed_kph, "
                    + "latitude = EXCLUDED.latitude, "
                    + "longitude = EXCLUDED.longitude, "
                    + "snapped_latitude = EXCLUDED.snapped_latitude, "
                    + "snapped_longitude = EXCLUDED.snapped_longitude, "
                    + "heading_deg = EXCLUDED.heading_deg, "
                    + "topic = EXCLUDED.topic, "
                    + "source_topic = EXCLUDED.source_topic, "
                    + "derivation = EXCLUDED.derivation, "
                    + "raw_record = EXCLUDED.raw_record";
        }

        private static void ensureSchema(Connection connection, Settings settings) throws SQLException {
            String schemaName = quoteIdentifier(settings.schemaName);
            String tableName = qualifiedTableName(settings.schemaName, settings.tableName);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                                + "captured_at TIMESTAMPTZ NOT NULL,"
                                + "device_id TEXT NOT NULL,"
                                + "source_session TEXT NOT NULL,"
                                + "track_id INTEGER NOT NULL,"
                                + "track_name TEXT NOT NULL,"
                                + "s_m DOUBLE PRECISION NOT NULL,"
                                + "progress_pct DOUBLE PRECISION,"
                                + "distance_to_track_m DOUBLE PRECISION,"
                                + "ground_speed_kph DOUBLE PRECISION,"
                                + "latitude DOUBLE PRECISION,"
                                + "longitude DOUBLE PRECISION,"
                                + "snapped_latitude DOUBLE PRECISION,"
                                + "snapped_longitude DOUBLE PRECISION,"
                                + "heading_deg DOUBLE PRECISION,"
                                + "topic TEXT,"
                                + "source_topic TEXT,"
                                + "derivation TEXT,"
                                + "raw_record JSONB NOT NULL,"
                                + "PRIMARY KEY (device_id, source_session, captured_at, track_id)"
                                + ")");
                statement.execute(
                        "SELECT create_hypertable('"
                                + settings.schemaName + "." + settings.tableName
                                + "', 'captured_at', if_not_exists => TRUE, migrate_data => TRUE)");
                statement.execute(
                        "CREATE INDEX IF NOT EXISTS track_position_samples_session_s_idx "
                                + "ON " + tableName + " (source_session, s_m)");
                statement.execute(
                        "CREATE INDEX IF NOT EXISTS track_position_samples_track_time_idx "
                                + "ON " + tableName + " (track_id, captured_at DESC)");
            }
        }
    }

    static final class Settings implements Serializable {

        private final String iggyUsername;
        private final String iggyPassword;
        private final URI httpApiUrl;
        private final String tcpHost;
        private final int tcpPort;
        private final String iggyStream;
        private final String inputTopic;
        private final String consumerGroup;
        private final int sourcePollBatchSize;
        private final long checkpointIntervalMillis;
        private final String dbHost;
        private final int dbPort;
        private final String dbName;
        private final String dbUser;
        private final String dbPassword;
        private final String schemaName;
        private final String tableName;

        private Settings(
                String iggyUsername,
                String iggyPassword,
                URI httpApiUrl,
                String tcpHost,
                int tcpPort,
                String iggyStream,
                String inputTopic,
                String consumerGroup,
                int sourcePollBatchSize,
                long checkpointIntervalMillis,
                String dbHost,
                int dbPort,
                String dbName,
                String dbUser,
                String dbPassword,
                String schemaName,
                String tableName) {
            this.iggyUsername = iggyUsername;
            this.iggyPassword = iggyPassword;
            this.httpApiUrl = httpApiUrl;
            this.tcpHost = tcpHost;
            this.tcpPort = tcpPort;
            this.iggyStream = iggyStream;
            this.inputTopic = inputTopic;
            this.consumerGroup = consumerGroup;
            this.sourcePollBatchSize = sourcePollBatchSize;
            this.checkpointIntervalMillis = checkpointIntervalMillis;
            this.dbHost = dbHost;
            this.dbPort = dbPort;
            this.dbName = dbName;
            this.dbUser = dbUser;
            this.dbPassword = dbPassword;
            this.schemaName = schemaName;
            this.tableName = tableName;
        }

        static Settings fromEnvironment() {
            String iggyConnectionString = requireEnv("IGGY_CONNECTION_STRING");
            ConnectionDetails connectionDetails = ConnectionDetails.fromConnectionString(iggyConnectionString);
            return new Settings(
                    connectionDetails.username,
                    connectionDetails.password,
                    connectionDetails.httpApiUrl,
                    connectionDetails.tcpHost,
                    connectionDetails.tcpPort,
                    envOrDefault("IGGY_STREAM", "can-pub-sub-probe"),
                    envOrDefault("TRACK_POSITION_TIMESCALE_SINK_JOB_INPUT_TOPIC", "telemetry.derived.track_position"),
                    envOrDefault("TRACK_POSITION_TIMESCALE_SINK_JOB_CONSUMER_GROUP", "track-position-timescale-sink"),
                    envInt("TRACK_POSITION_TIMESCALE_SINK_JOB_SOURCE_POLL_BATCH_SIZE", 100),
                    envLong("TRACK_POSITION_TIMESCALE_SINK_JOB_CHECKPOINT_INTERVAL_MS", 60000L),
                    envOrDefault("TRACK_POSITION_TIMESCALE_SINK_DB_HOST", "timescaledb.timescaledb.svc.cluster.local"),
                    envInt("TRACK_POSITION_TIMESCALE_SINK_DB_PORT", 5432),
                    envOrDefault("TRACK_POSITION_TIMESCALE_SINK_DB_NAME", "postgres"),
                    requireEnv("TRACK_POSITION_TIMESCALE_SINK_DB_USER"),
                    requireEnv("TRACK_POSITION_TIMESCALE_SINK_DB_PASSWORD"),
                    envOrDefault("TRACK_POSITION_TIMESCALE_SINK_DB_SCHEMA", "telemetry"),
                    envOrDefault("TRACK_POSITION_TIMESCALE_SINK_DB_TABLE", "track_position_samples"));
        }

        IggyConnectionConfig toIggyConnectionConfig() {
            return IggyConnectionConfig.builder()
                    .serverAddress(tcpHost + ":" + tcpPort)
                    .username(iggyUsername)
                    .password(iggyPassword)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .build();
        }

        URI consumerGroupPath() {
            return httpApiUrl.resolve(
                    "/streams/" + iggyStream + "/topics/" + inputTopic + "/consumer-groups");
        }

        String jdbcUrl() {
            return "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;
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
    }

    static final class ConnectionDetails implements Serializable {

        private final String username;
        private final String password;
        private final URI httpApiUrl;
        private final String tcpHost;
        private final int tcpPort;

        private ConnectionDetails(String username, String password, URI httpApiUrl, String tcpHost, int tcpPort) {
            this.username = username;
            this.password = password;
            this.httpApiUrl = httpApiUrl;
            this.tcpHost = tcpHost;
            this.tcpPort = tcpPort;
        }

        static ConnectionDetails fromConnectionString(String connectionString) {
            URI uri = URI.create(connectionString);
            String userInfo = uri.getUserInfo();
            if (userInfo == null || !userInfo.contains(":")) {
                throw new IllegalArgumentException("IGGY_CONNECTION_STRING must contain username and password");
            }
            String[] userInfoParts = userInfo.split(":", 2);
            URI httpApiUrl = URI.create("http://" + uri.getHost() + ":3000");
            int tcpPort = uri.getPort() > 0 ? uri.getPort() : 8090;
            return new ConnectionDetails(userInfoParts[0], userInfoParts[1], httpApiUrl, uri.getHost(), tcpPort);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ConsumerGroupMetadata implements Serializable {

        private String name;

        public ConsumerGroupMetadata() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static final class TrackPositionSample implements Serializable {

        final String deviceId;
        final String sourceSession;
        final Instant capturedAt;
        final int trackId;
        final String trackName;
        final double sMeters;
        final Double progressPct;
        final Double distanceToTrackMeters;
        final Double groundSpeedKph;
        final Double latitude;
        final Double longitude;
        final Double snappedLatitude;
        final Double snappedLongitude;
        final Double headingDeg;
        final String topic;
        final String sourceTopic;
        final String derivation;
        final Map rawRecord;

        private TrackPositionSample(
                String deviceId,
                String sourceSession,
                Instant capturedAt,
                int trackId,
                String trackName,
                double sMeters,
                Double progressPct,
                Double distanceToTrackMeters,
                Double groundSpeedKph,
                Double latitude,
                Double longitude,
                Double snappedLatitude,
                Double snappedLongitude,
                Double headingDeg,
                String topic,
                String sourceTopic,
                String derivation,
                Map rawRecord) {
            this.deviceId = deviceId;
            this.sourceSession = sourceSession;
            this.capturedAt = capturedAt;
            this.trackId = trackId;
            this.trackName = trackName;
            this.sMeters = sMeters;
            this.progressPct = progressPct;
            this.distanceToTrackMeters = distanceToTrackMeters;
            this.groundSpeedKph = groundSpeedKph;
            this.latitude = latitude;
            this.longitude = longitude;
            this.snappedLatitude = snappedLatitude;
            this.snappedLongitude = snappedLongitude;
            this.headingDeg = headingDeg;
            this.topic = topic;
            this.sourceTopic = sourceTopic;
            this.derivation = derivation;
            this.rawRecord = rawRecord;
        }
    }
}
