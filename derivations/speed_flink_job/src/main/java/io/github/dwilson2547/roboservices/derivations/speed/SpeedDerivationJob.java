package io.github.dwilson2547.roboservices.derivations.speed;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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

    private SpeedDerivationJob() {
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.fromEnvironment();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(settings.checkpointIntervalMillis);

        IggyConnectionConfig connectionConfig = settings.toIggyConnectionConfig();

        DataStream<GpsEnvelope> input = env.fromSource(
                IggySource.<GpsEnvelope>builder()
                        .setConnectionConfig(connectionConfig)
                        .setStreamId(settings.iggyStream)
                        .setTopicId(settings.inputTopic)
                        .setConsumerGroup(settings.consumerGroup)
                        .setDeserializer(new JsonDeserializationSchema<>(GpsEnvelope.class))
                        .setPollBatchSize(settings.sourcePollBatchSize)
                        .build(),
                WatermarkStrategy.noWatermarks(),
                "gps-source",
                TypeInformation.of(GpsEnvelope.class));

        DataStream<SpeedSample> samples = input
                .flatMap(new SpeedSampleExtractor())
                .name("extract-speed-samples")
                .returns(TypeInformation.of(SpeedSample.class))
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<SpeedSample>forBoundedOutOfOrderness(
                                        Duration.ofSeconds(settings.maxOutOfOrdernessSeconds))
                                .withTimestampAssigner(new SpeedSampleTimestampAssigner()));

        DataStream<DerivedSpeedRecord> derived = samples
                .keyBy(SpeedSample::getDeviceId)
                .window(TumblingEventTimeWindows.of(Duration.ofSeconds(settings.windowSeconds)))
                .aggregate(
                        new SpeedAverageAggregate(),
                        new SpeedWindowProcessFunction(settings.inputTopic, settings.outputTopic))
                .name("compute-average-speed");

        derived
                .sinkTo(IggySink.<DerivedSpeedRecord>builder()
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

    static SpeedSample requireSpeedSample(GpsEnvelope envelope) {
        String deviceId = requireText(envelope.getDeviceId(), "device_id");
        String sourceSession = requireText(envelope.getSourceSession(), "source_session");
        GpsPayload payload = Objects.requireNonNull(envelope.getPayload(), "payload must be present");
        Double speed = payload.getGroundSpeedKph();
        if (speed == null) {
            throw new IllegalArgumentException("payload.ground_speed_kph must be present");
        }
        String timestamp = envelope.getCapturedAt();
        if (timestamp == null || timestamp.isBlank()) {
            timestamp = envelope.getReceivedAt();
        }
        if (timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException("captured_at or received_at must be present");
        }
        return new SpeedSample(deviceId, sourceSession, Instant.parse(timestamp).toEpochMilli(), speed);
    }

    static DerivedSpeedRecord buildDerivedSpeedRecord(
            String deviceId,
            SpeedAccumulator accumulator,
            long windowStartMillis,
            long windowEndMillis,
            String inputTopic,
            String outputTopic) {
        return new DerivedSpeedRecord(
                deviceId,
                accumulator.getSourceSession(),
                Instant.ofEpochMilli(windowStartMillis).toString(),
                Instant.ofEpochMilli(windowEndMillis).toString(),
                accumulator.getCount(),
                accumulator.averageSpeedKph(),
                UNIT,
                inputTopic,
                outputTopic,
                DERIVATION_NAME);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be present");
        }
        return value;
    }

    static final class SpeedSampleExtractor implements FlatMapFunction<GpsEnvelope, SpeedSample> {

        private static final Logger LOG = LoggerFactory.getLogger(SpeedSampleExtractor.class);

        @Override
        public void flatMap(GpsEnvelope value, Collector<SpeedSample> out) {
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
            extends ProcessWindowFunction<SpeedAccumulator, DerivedSpeedRecord, String, TimeWindow> {

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
                Collector<DerivedSpeedRecord> out) {
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
            return new Settings(
                    requireEnv("IGGY_CONNECTION_STRING"),
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
            URI uri = URI.create(iggyConnectionString);
            String userInfo = uri.getUserInfo();
            if (userInfo == null || !userInfo.contains(":")) {
                throw new IllegalArgumentException("IGGY_CONNECTION_STRING must contain username and password");
            }
            String[] userInfoParts = userInfo.split(":", 2);
            String serverAddress = uri.getHost() + ":" + uri.getPort();
            return IggyConnectionConfig.builder()
                    .serverAddress(serverAddress)
                    .username(userInfoParts[0])
                    .password(userInfoParts[1])
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

    public static final class GpsEnvelope implements Serializable {

        @JsonProperty("device_id")
        private String deviceId;

        @JsonProperty("source_session")
        private String sourceSession;

        @JsonProperty("captured_at")
        private String capturedAt;

        @JsonProperty("received_at")
        private String receivedAt;

        @JsonProperty("payload")
        private GpsPayload payload;

        public GpsEnvelope() {
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public String getSourceSession() {
            return sourceSession;
        }

        public void setSourceSession(String sourceSession) {
            this.sourceSession = sourceSession;
        }

        public String getCapturedAt() {
            return capturedAt;
        }

        public void setCapturedAt(String capturedAt) {
            this.capturedAt = capturedAt;
        }

        public String getReceivedAt() {
            return receivedAt;
        }

        public void setReceivedAt(String receivedAt) {
            this.receivedAt = receivedAt;
        }

        public GpsPayload getPayload() {
            return payload;
        }

        public void setPayload(GpsPayload payload) {
            this.payload = payload;
        }
    }

    public static final class GpsPayload implements Serializable {

        @JsonProperty("ground_speed_kph")
        private Double groundSpeedKph;

        public GpsPayload() {
        }

        public GpsPayload(Double groundSpeedKph) {
            this.groundSpeedKph = groundSpeedKph;
        }

        public Double getGroundSpeedKph() {
            return groundSpeedKph;
        }

        public void setGroundSpeedKph(Double groundSpeedKph) {
            this.groundSpeedKph = groundSpeedKph;
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

    public static final class DerivedSpeedRecord implements Serializable {

        @JsonProperty("device_id")
        private String deviceId;

        @JsonProperty("source_session")
        private String sourceSession;

        @JsonProperty("window_start")
        private String windowStart;

        @JsonProperty("window_end")
        private String windowEnd;

        @JsonProperty("sample_count")
        private int sampleCount;

        @JsonProperty("average_speed_kph")
        private double averageSpeedKph;

        @JsonProperty("unit")
        private String unit;

        @JsonProperty("source_topic")
        private String sourceTopic;

        @JsonProperty("topic")
        private String topic;

        @JsonProperty("derivation")
        private String derivation;

        public DerivedSpeedRecord() {
        }

        public DerivedSpeedRecord(
                String deviceId,
                String sourceSession,
                String windowStart,
                String windowEnd,
                int sampleCount,
                double averageSpeedKph,
                String unit,
                String sourceTopic,
                String topic,
                String derivation) {
            this.deviceId = deviceId;
            this.sourceSession = sourceSession;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.sampleCount = sampleCount;
            this.averageSpeedKph = averageSpeedKph;
            this.unit = unit;
            this.sourceTopic = sourceTopic;
            this.topic = topic;
            this.derivation = derivation;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getSourceSession() {
            return sourceSession;
        }

        public String getWindowStart() {
            return windowStart;
        }

        public String getWindowEnd() {
            return windowEnd;
        }

        public int getSampleCount() {
            return sampleCount;
        }

        public double getAverageSpeedKph() {
            return averageSpeedKph;
        }

        public String getUnit() {
            return unit;
        }

        public String getSourceTopic() {
            return sourceTopic;
        }

        public String getTopic() {
            return topic;
        }

        public String getDerivation() {
            return derivation;
        }
    }
}
