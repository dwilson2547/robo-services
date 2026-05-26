package io.github.dwilson2547.roboservices.derivations.speed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dwilson2547.roboservices.derivations.speed.SpeedDerivationJob.DerivedSpeedRecord;
import io.github.dwilson2547.roboservices.derivations.speed.SpeedDerivationJob.GpsEnvelope;
import io.github.dwilson2547.roboservices.derivations.speed.SpeedDerivationJob.GpsPayload;
import io.github.dwilson2547.roboservices.derivations.speed.SpeedDerivationJob.SpeedAccumulator;
import io.github.dwilson2547.roboservices.derivations.speed.SpeedDerivationJob.SpeedSample;
import org.junit.jupiter.api.Test;

class SpeedDerivationJobTest {

    @Test
    void requireSpeedSampleUsesCapturedAtWhenPresent() {
        GpsEnvelope envelope = envelope("gps-01", "bench-a", "2026-05-25T22:57:46Z", "2026-05-25T22:57:47Z", 12.5);

        SpeedSample sample = SpeedDerivationJob.requireSpeedSample(envelope);

        assertEquals("gps-01", sample.getDeviceId());
        assertEquals("bench-a", sample.getSourceSession());
        assertEquals(12.5, sample.getGroundSpeedKph());
        assertEquals(1779749866000L, sample.getEventTimeMillis());
    }

    @Test
    void requireSpeedSampleFallsBackToReceivedAt() {
        GpsEnvelope envelope = envelope("gps-01", "bench-a", null, "2026-05-25T22:57:47Z", 12.5);

        SpeedSample sample = SpeedDerivationJob.requireSpeedSample(envelope);

        assertEquals(1779749867000L, sample.getEventTimeMillis());
    }

    @Test
    void buildDerivedSpeedRecordComputesAverage() {
        SpeedAccumulator accumulator = new SpeedAccumulator();
        accumulator.add(new SpeedSample("gps-01", "bench-a", 1L, 10.0));
        accumulator.add(new SpeedSample("gps-01", "bench-a", 2L, 20.0));

        DerivedSpeedRecord record = SpeedDerivationJob.buildDerivedSpeedRecord(
                "gps-01",
                accumulator,
                1779749860000L,
                1779749870000L,
                "telemetry.raw.gps",
                "telemetry.derived.speed");

        assertEquals("gps-01", record.getDeviceId());
        assertEquals("bench-a", record.getSourceSession());
        assertEquals("2026-05-25T22:57:40Z", record.getWindowStart());
        assertEquals("2026-05-25T22:57:50Z", record.getWindowEnd());
        assertEquals(2, record.getSampleCount());
        assertEquals(15.0, record.getAverageSpeedKph());
        assertEquals("telemetry.raw.gps", record.getSourceTopic());
        assertEquals("telemetry.derived.speed", record.getTopic());
    }

    @Test
    void requireSpeedSampleRejectsMissingSpeed() {
        GpsEnvelope envelope = envelope("gps-01", "bench-a", "2026-05-25T22:57:46Z", null, null);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> SpeedDerivationJob.requireSpeedSample(envelope));

        assertEquals("payload.ground_speed_kph must be present", error.getMessage());
    }

    private static GpsEnvelope envelope(
            String deviceId,
            String sourceSession,
            String capturedAt,
            String receivedAt,
            Double speed) {
        GpsEnvelope envelope = new GpsEnvelope();
        envelope.setDeviceId(deviceId);
        envelope.setSourceSession(sourceSession);
        envelope.setCapturedAt(capturedAt);
        envelope.setReceivedAt(receivedAt);
        envelope.setPayload(new GpsPayload(speed));
        return envelope;
    }
}
