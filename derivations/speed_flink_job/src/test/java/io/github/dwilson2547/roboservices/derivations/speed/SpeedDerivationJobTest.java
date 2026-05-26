package io.github.dwilson2547.roboservices.derivations.speed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dwilson2547.roboservices.derivations.speed.SpeedDerivationJob.SpeedAccumulator;
import io.github.dwilson2547.roboservices.derivations.speed.SpeedDerivationJob.SpeedSample;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpeedDerivationJobTest {

    @Test
    void requireSpeedSampleUsesCapturedAtWhenPresent() {
        Map<String, Object> envelope =
                envelope("gps-01", "bench-a", "2026-05-25T22:57:46Z", "2026-05-25T22:57:47Z", 12.5);

        SpeedSample sample = SpeedDerivationJob.requireSpeedSample(envelope);

        assertEquals("gps-01", sample.getDeviceId());
        assertEquals("bench-a", sample.getSourceSession());
        assertEquals(12.5, sample.getGroundSpeedKph());
        assertEquals(1779749866000L, sample.getEventTimeMillis());
    }

    @Test
    void requireSpeedSampleFallsBackToReceivedAt() {
        Map<String, Object> envelope = envelope("gps-01", "bench-a", null, "2026-05-25T22:57:47Z", 12.5);

        SpeedSample sample = SpeedDerivationJob.requireSpeedSample(envelope);

        assertEquals(1779749867000L, sample.getEventTimeMillis());
    }

    @Test
    void buildDerivedSpeedRecordComputesAverage() {
        SpeedAccumulator accumulator = new SpeedAccumulator();
        accumulator.add(new SpeedSample("gps-01", "bench-a", 1L, 10.0));
        accumulator.add(new SpeedSample("gps-01", "bench-a", 2L, 20.0));

        Map record = SpeedDerivationJob.buildDerivedSpeedRecord(
                "gps-01",
                accumulator,
                1779749860000L,
                1779749870000L,
                "telemetry.raw.gps",
                "telemetry.derived.speed");

        assertEquals("gps-01", record.get("device_id"));
        assertEquals("bench-a", record.get("source_session"));
        assertEquals("2026-05-25T22:57:40Z", record.get("window_start"));
        assertEquals("2026-05-25T22:57:50Z", record.get("window_end"));
        assertEquals(2, record.get("sample_count"));
        assertEquals(15.0, record.get("average_speed_kph"));
        assertEquals("telemetry.raw.gps", record.get("source_topic"));
        assertEquals("telemetry.derived.speed", record.get("topic"));
    }

    @Test
    void requireSpeedSampleRejectsMissingSpeed() {
        Map<String, Object> envelope = envelope("gps-01", "bench-a", "2026-05-25T22:57:46Z", null, null);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> SpeedDerivationJob.requireSpeedSample(envelope));

        assertEquals("payload.ground_speed_kph must be present", error.getMessage());
    }

    private static Map<String, Object> envelope(
            String deviceId,
            String sourceSession,
            String capturedAt,
            String receivedAt,
            Double speed) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ground_speed_kph", speed);

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("device_id", deviceId);
        envelope.put("source_session", sourceSession);
        envelope.put("captured_at", capturedAt);
        envelope.put("received_at", receivedAt);
        envelope.put("payload", payload);
        return envelope;
    }
}
