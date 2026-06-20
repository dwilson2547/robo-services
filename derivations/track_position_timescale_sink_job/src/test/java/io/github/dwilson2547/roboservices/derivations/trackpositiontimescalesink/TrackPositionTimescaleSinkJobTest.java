package io.github.dwilson2547.roboservices.derivations.trackpositiontimescalesink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrackPositionTimescaleSinkJobTest {

    @Test
    void requireTrackPositionSampleDecodesBase64Envelope() {
        String payload = "{\"device_id\":\"device-1\",\"source_session\":\"session-1\","
                + "\"captured_at\":\"2026-06-20T09:17:01Z\",\"track_id\":21,"
                + "\"track_name\":\"Gravel Creek Oval POC\",\"s_m\":42.5,\"progress_pct\":5.0,"
                + "\"distance_to_track_m\":0.3,\"ground_speed_kph\":88.1,\"latitude\":37.0,"
                + "\"longitude\":-112.0,\"snapped_latitude\":37.0,\"snapped_longitude\":-112.0,"
                + "\"heading_deg\":12.0,\"topic\":\"telemetry.derived.track_position\","
                + "\"source_topic\":\"telemetry.raw.gps\",\"derivation\":\"track_position\"}";

        TrackPositionTimescaleSinkJob.TrackPositionSample sample =
                TrackPositionTimescaleSinkJob.requireTrackPositionSample(Map.of(
                        "payload_b64",
                        Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))));

        assertEquals("device-1", sample.deviceId);
        assertEquals("session-1", sample.sourceSession);
        assertEquals(21, sample.trackId);
        assertEquals("Gravel Creek Oval POC", sample.trackName);
        assertEquals(42.5, sample.sMeters);
    }

    @Test
    void qualifiedTableNameQuotesIdentifiers() {
        assertEquals(
                "\"telemetry\".\"track_position_samples\"",
                TrackPositionTimescaleSinkJob.qualifiedTableName("telemetry", "track_position_samples"));
    }
}
