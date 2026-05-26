package io.github.dwilson2547.roboservices.derivations.speed;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.iggy.connector.config.IggyConnectionConfig;
import org.apache.iggy.connector.flink.sink.IggySink;
import org.apache.iggy.connector.flink.source.IggySource;
import org.apache.iggy.connector.serialization.JsonDeserializationSchema;
import org.apache.iggy.connector.serialization.JsonSerializationSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SpeedDerivationJob {

    private static final Logger LOG = LoggerFactory.getLogger(SpeedDerivationJob.class);
    private static final String DERIVATION_NAME = "average_ground_speed_kph";
    private static final String UNIT = "kph";
    private static final long DEFAULT_MESSAGE_EXPIRY_MICROS = 21_600_000_000L;
    private static final long DEFAULT_MAX_TOPIC_SIZE = 0L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SpeedDerivationJob() {
    }

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
                        .setDeserializer(new JsonDeserializationSchema<>(Map.class))
                        .setPollBatchSize(settings.sourcePollBatchSize)
                        .build(),
                WatermarkStrategy.noWatermarks(),
                "gps-source",
                TypeInformation.of(Map.class));

        DataStream<SpeedSample> samples = input
                .flatMap(new SpeedSampleExtractor())
                .name("extract-speed-samples")
                .returns(TypeInformation.of(SpeedSample.class))
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<SpeedSample>forBoundedOutOfOrderness(
                                        Duration.ofSeconds(settings.maxOutOfOrdernessSeconds))
                                .withTimestampAssigner(new SpeedSampleTimestampAssigner()));

        DataStream<Map> derived = samples
                .keyBy(SpeedSample::getDeviceId)
                .window(TumblingEventTimeWindows.of(Duration.ofSeconds(settings.windowSeconds)))
                .aggregate(
                        new SpeedAverageAggregate(),
                        new SpeedWindowProcessFunction(settings.inputTopic, settings.outputTopic))
                .name("compute-average-speed")
                .returns(TypeInformation.of(Map.class));

        derived
                .sinkTo(IggySink.<Map>builder()
                        .setConnectionConfig(connectionConfig)
                        .setStreamId(settings.iggyStream)
                        .setTopicId(settings.outputTopic)
                        .setSerializer(new JsonSerializationSchema<>())
                        .setBatchSize(settings.sinkBatchSize)
                        .setFlushInterval(Duration.ofSeconds(settings.sinkFlushIntervalSeconds))
                        .withBalancedPartitioning()
                        .build())
                .name("speed-sink");

        env.execute("robo-services-speed-derivation");
    }

    private static void ensureIggyMetadata(Settings settings) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        String accessToken = login(settings, client);
        ensureConsumerGroup(settings, client, accessToken);
        ensureTopic(settings, client, accessToken);
    }

    private static void ensureConsumerGroup(Settings settings, HttpClient client, String accessToken)
            throws IOException, InterruptedException {
        HttpRequest listRequest = authorizedRequest(settings.consumerGroupPath())
                .GET()
                .build();
        HttpResponse<String> listResponse = send(settings, client, listRequest, accessToken);
        List<ConsumerGroupMetadata> groups =
                OBJECT_MAPPER.readValue(listResponse.body(), new TypeReference<List<ConsumerGroupMetadata>>() {});
        boolean exists = groups.stream().anyMatch(group -> settings.consumerGroup.equals(group.getName()));
        if (exists) {
            return;
        }

        HttpRequest createRequest = authorizedRequest(settings.consumerGroupPath())
                .POST(HttpRequest.BodyPublishers.ofString(
                        OBJECT_MAPPER.writeValueAsString(Map.of("name", settings.consumerGroup))))
                .build();
        HttpResponse<String> createResponse = send(settings, client, createRequest, accessToken);
        if (createResponse.statusCode() != 201) {
            throw new IllegalStateException("Failed to create consumer group: " + createResponse.body());
        }
        LOG.info("Created consumer group {} for stream {} topic {}", settings.consumerGroup, settings.iggyStream,
                settings.inputTopic);
    }

    private static void ensureTopic(Settings settings, HttpClient client, String accessToken)
            throws IOException, InterruptedException {
        HttpRequest streamRequest = authorizedRequest(settings.streamPath())
                .GET()
                .build();
        HttpResponse<String> streamResponse = send(settings, client, streamRequest, accessToken);
        StreamMetadata stream = OBJECT_MAPPER.readValue(streamResponse.body(), StreamMetadata.class);
        boolean exists = stream.getTopics().stream().anyMatch(topic -> settings.outputTopic.equals(topic.getName()));
        if (exists) {
            return;
        }

        HttpRequest createRequest = authorizedRequest(settings.topicsPath())
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                        "name", settings.outputTopic,
                        "partitions_count", 1,
                        "compression_algorithm", "none",
                        "message_expiry", DEFAULT_MESSAGE_EXPIRY_MICROS,
                        "max_topic_size", DEFAULT_MAX_TOPIC_SIZE,
                        "replication_factor", 1))))
                .build();
        HttpResponse<String> createResponse = send(settings, client, createRequest, accessToken);
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            throw new IllegalStateException("Failed to create topic: " + createResponse.body());
        }
        LOG.info("Created topic {} on stream {}", settings.outputTopic, settings.iggyStream);
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

    private static HttpRequest.Builder authorizedRequest(URI uri) {
        return HttpRequest.newBuilder(uri);
    }

    static SpeedSample requireSpeedSample(Map envelope) {
        String deviceId = requireText(asString(envelope.get("device_id")), "device_id");
        String sourceSession = requireText(asString(envelope.get("source_session")), "source_session");
        Object payloadObject = envelope.get("payload");
        if (!(payloadObject instanceof Map<?, ?> payload)) {
            throw new IllegalArgumentException("payload must be present");
        }
        Object speedObject = payload.get("ground_speed_kph");
        if (!(speedObject instanceof Number speed)) {
            throw new IllegalArgumentException("payload.ground_speed_kph must be present");
        }
        String timestamp = asString(envelope.get("captured_at"));
        if (timestamp == null || timestamp.isBlank()) {
            timestamp = asString(envelope.get("received_at"));
        }
        if (timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException("captured_at or received_at must be present");
        }
        return new SpeedSample(deviceId, sourceSession, Instant.parse(timestamp).toEpochMilli(), speed.doubleValue());
    }

    static Map buildDerivedSpeedRecord(
            String deviceId,
            SpeedAccumulator accumulator,
            long windowStartMillis,
            long windowEndMillis,
            String inputTopic,
            String outputTopic) {
        return Map.of(
                "device_id", deviceId,
                "source_session", accumulator.getSourceSession(),
                "window_start", Instant.ofEpochMilli(windowStartMillis).toString(),
                "window_end", Instant.ofEpochMilli(windowEndMillis).toString(),
                "sample_count", accumulator.getCount(),
                "average_speed_kph", accumulator.averageSpeedKph(),
                "unit", UNIT,
                "source_topic", inputTopic,
                "topic", outputTopic,
                "derivation", DERIVATION_NAME);
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

    static final class SpeedSampleExtractor implements FlatMapFunction<Map, SpeedSample> {

        private static final Logger LOG = LoggerFactory.getLogger(SpeedSampleExtractor.class);

        @Override
        public void flatMap(Map value, Collector<SpeedSample> out) {
            try {
                out.collect(requireSpeedSample(value));
            } catch (RuntimeException exc) {
                LOG.warn("Skipping malformed gps message: {}", exc.getMessage());
            }
        }
    }

    static final class SpeedSampleTimestampAssigner implements SerializableTimestampAssigner<SpeedSample> {

        @Override
        public long extractTimestamp(SpeedSample element, long recordTimestamp) {
            return element.getEventTimeMillis();
        }
    }

    static final class SpeedAverageAggregate
            implements AggregateFunction<SpeedSample, SpeedAccumulator, SpeedAccumulator> {

        @Override
        public SpeedAccumulator createAccumulator() {
            return new SpeedAccumulator();
        }

        @Override
        public SpeedAccumulator add(SpeedSample value, SpeedAccumulator accumulator) {
            accumulator.add(value);
            return accumulator;
        }

        @Override
        public SpeedAccumulator getResult(SpeedAccumulator accumulator) {
            return accumulator;
        }

        @Override
        public SpeedAccumulator merge(SpeedAccumulator a, SpeedAccumulator b) {
            a.merge(b);
            return a;
        }
    }

    static final class SpeedWindowProcessFunction
            extends ProcessWindowFunction<SpeedAccumulator, Map, String, TimeWindow> {

        private final String inputTopic;
        private final String outputTopic;

        SpeedWindowProcessFunction(String inputTopic, String outputTopic) {
            this.inputTopic = inputTopic;
            this.outputTopic = outputTopic;
        }

        @Override
        public void process(
                String key,
                Context context,
                Iterable<SpeedAccumulator> elements,
                Collector<Map> out) {
            SpeedAccumulator accumulator = elements.iterator().next();
            out.collect(buildDerivedSpeedRecord(
                    key,
                    accumulator,
                    context.window().getStart(),
                    context.window().getEnd(),
                    inputTopic,
                    outputTopic));
        }
    }

    static final class Settings {

        private final String iggyConnectionString;
        private final String iggyUsername;
        private final String iggyPassword;
        private final URI httpApiUrl;
        private final String iggyStream;
        private final String inputTopic;
        private final String outputTopic;
        private final String consumerGroup;
        private final int windowSeconds;
        private final int maxOutOfOrdernessSeconds;
        private final int sourcePollBatchSize;
        private final int sinkBatchSize;
        private final int sinkFlushIntervalSeconds;
        private final long checkpointIntervalMillis;

        private Settings(
                String iggyConnectionString,
                String iggyUsername,
                String iggyPassword,
                URI httpApiUrl,
                String iggyStream,
                String inputTopic,
                String outputTopic,
                String consumerGroup,
                int windowSeconds,
                int maxOutOfOrdernessSeconds,
                int sourcePollBatchSize,
                int sinkBatchSize,
                int sinkFlushIntervalSeconds,
                long checkpointIntervalMillis) {
            this.iggyConnectionString = iggyConnectionString;
            this.iggyUsername = iggyUsername;
            this.iggyPassword = iggyPassword;
            this.httpApiUrl = httpApiUrl;
            this.iggyStream = iggyStream;
            this.inputTopic = inputTopic;
            this.outputTopic = outputTopic;
            this.consumerGroup = consumerGroup;
            this.windowSeconds = windowSeconds;
            this.maxOutOfOrdernessSeconds = maxOutOfOrdernessSeconds;
            this.sourcePollBatchSize = sourcePollBatchSize;
            this.sinkBatchSize = sinkBatchSize;
            this.sinkFlushIntervalSeconds = sinkFlushIntervalSeconds;
            this.checkpointIntervalMillis = checkpointIntervalMillis;
        }

        static Settings fromEnvironment() {
            String iggyConnectionString = requireEnv("IGGY_CONNECTION_STRING");
            ConnectionDetails connectionDetails = ConnectionDetails.fromConnectionString(iggyConnectionString);
            return new Settings(
                    iggyConnectionString,
                    connectionDetails.username,
                    connectionDetails.password,
                    connectionDetails.httpApiUrl,
                    envOrDefault("IGGY_STREAM", "can-pub-sub-probe"),
                    envOrDefault("SPEED_JOB_INPUT_TOPIC", "telemetry.raw.gps"),
                    envOrDefault("SPEED_JOB_OUTPUT_TOPIC", "telemetry.derived.speed"),
                    envOrDefault("SPEED_JOB_CONSUMER_GROUP", "speed-derivation"),
                    envInt("SPEED_JOB_WINDOW_SECONDS", 10),
                    envInt("SPEED_JOB_MAX_OUT_OF_ORDERNESS_SECONDS", 5),
                    envInt("SPEED_JOB_SOURCE_POLL_BATCH_SIZE", 100),
                    envInt("SPEED_JOB_SINK_BATCH_SIZE", 100),
                    envInt("SPEED_JOB_SINK_FLUSH_INTERVAL_SECONDS", 5),
                    envLong("SPEED_JOB_CHECKPOINT_INTERVAL_MS", 60000L));
        }

        IggyConnectionConfig toIggyConnectionConfig() {
            String serverAddress = httpApiUrl.getHost() + ":8090";
            return IggyConnectionConfig.builder()
                    .serverAddress(serverAddress)
                    .username(iggyUsername)
                    .password(iggyPassword)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .build();
        }

        URI consumerGroupPath() {
            return httpApiUrl.resolve(
                    "/streams/" + iggyStream + "/topics/" + inputTopic + "/consumer-groups");
        }

        URI topicsPath() {
            return httpApiUrl.resolve("/streams/" + iggyStream + "/topics");
        }

        URI streamPath() {
            return httpApiUrl.resolve("/streams/" + iggyStream);
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

    static final class ConnectionDetails {

        private final String username;
        private final String password;
        private final URI httpApiUrl;

        private ConnectionDetails(String username, String password, URI httpApiUrl) {
            this.username = username;
            this.password = password;
            this.httpApiUrl = httpApiUrl;
        }

        static ConnectionDetails fromConnectionString(String connectionString) {
            URI uri = URI.create(connectionString);
            String userInfo = uri.getUserInfo();
            if (userInfo == null || !userInfo.contains(":")) {
                throw new IllegalArgumentException("IGGY_CONNECTION_STRING must contain username and password");
            }
            String[] userInfoParts = userInfo.split(":", 2);
            URI httpApiUrl = URI.create("http://" + uri.getHost() + ":3000");
            return new ConnectionDetails(userInfoParts[0], userInfoParts[1], httpApiUrl);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class StreamMetadata implements Serializable {

        private List<TopicMetadata> topics = List.of();

        public StreamMetadata() {
        }

        public List<TopicMetadata> getTopics() {
            return topics;
        }

        public void setTopics(List<TopicMetadata> topics) {
            this.topics = topics;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TopicMetadata implements Serializable {

        private String name;

        public TopicMetadata() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static final class SpeedAccumulator implements Serializable {

        private double totalSpeedKph;
        private int count;
        private String sourceSession = "";

        void add(SpeedSample sample) {
            totalSpeedKph += sample.getGroundSpeedKph();
            count += 1;
            sourceSession = sample.getSourceSession();
        }

        void merge(SpeedAccumulator other) {
            totalSpeedKph += other.totalSpeedKph;
            count += other.count;
            if (!other.sourceSession.isBlank()) {
                sourceSession = other.sourceSession;
            }
        }

        int getCount() {
            return count;
        }

        String getSourceSession() {
            return sourceSession;
        }

        double averageSpeedKph() {
            return totalSpeedKph / count;
        }
    }

    public static final class SpeedSample implements Serializable {

        private final String deviceId;
        private final String sourceSession;
        private final long eventTimeMillis;
        private final double groundSpeedKph;

        public SpeedSample(String deviceId, String sourceSession, long eventTimeMillis, double groundSpeedKph) {
            this.deviceId = deviceId;
            this.sourceSession = sourceSession;
            this.eventTimeMillis = eventTimeMillis;
            this.groundSpeedKph = groundSpeedKph;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getSourceSession() {
            return sourceSession;
        }

        public long getEventTimeMillis() {
            return eventTimeMillis;
        }

        public double getGroundSpeedKph() {
            return groundSpeedKph;
        }
    }

}
