package io.github.dwilson2547.roboservices.derivations.trackposition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dwilson2547.roboservices.derivations.trackposition.TrackPositionDerivationJob.GeoPoint;
import io.github.dwilson2547.roboservices.derivations.trackposition.TrackPositionDerivationJob.GpsSample;
import io.github.dwilson2547.roboservices.derivations.trackposition.TrackPositionDerivationJob.TrackDefinition;
import io.github.dwilson2547.roboservices.derivations.trackposition.TrackPositionDerivationJob.TrackMatch;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrackPositionDerivationJobTest {

    @Test
    void requireGpsSampleDecodesPayloadBase64Wrapper() {
        Map<String, Object> wrappedEnvelope = Map.of(
                "payload_b64",
                Base64.getEncoder().encodeToString(
                        """
                        {"device_id":"gps-01","source_session":"bench-a","captured_at":"2026-05-25T22:57:46Z","payload":{"latitude":40.0,"longitude":-111.0,"ground_speed_kph":12.5,"heading_deg":90.0}}
                        """.strip().getBytes(StandardCharsets.UTF_8)));

        GpsSample sample = TrackPositionDerivationJob.requireGpsSample(wrappedEnvelope);

        assertEquals("gps-01", sample.deviceId);
        assertEquals("bench-a", sample.sourceSession);
        assertEquals(40.0, sample.latitude);
        assertEquals(-111.0, sample.longitude);
        assertEquals(12.5, sample.groundSpeedKph);
        assertEquals(90.0, sample.headingDeg);
    }

    @Test
    void parseTrackDefinitionsKeepsOnlyLineBasedGeometry() throws Exception {
        String json = """
                [
                  {
                    "id": 1,
                    "name": "Line Track",
                    "geometry": {
                      "type": "Feature",
                      "geometry": {
                        "type": "LineString",
                        "coordinates": [[-111.0, 40.0], [-111.0, 40.001]]
                      }
                    }
                  },
                  {
                    "id": 2,
                    "name": "Polygon Track",
                    "geometry": {
                      "type": "Feature",
                      "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-111.0, 40.0], [-111.0, 40.001], [-111.001, 40.001], [-111.0, 40.0]]]
                      }
                    }
                  }
                ]
                """;

        List<TrackDefinition> tracks = TrackPositionDerivationJob.parseTrackDefinitions(json);

        assertEquals(1, tracks.size());
        assertEquals("Line Track", tracks.get(0).name);
    }

    @Test
    void findBestMatchComputesTrackRelativeS() {
        TrackDefinition track = new TrackDefinition(
                1,
                "Test Track",
                List.of(
                        new GeoPoint(40.0, -111.0),
                        new GeoPoint(40.001, -111.0),
                        new GeoPoint(40.002, -111.0)),
                TrackPositionDerivationJob.haversineMeters(40.0, -111.0, 40.001, -111.0)
                        + TrackPositionDerivationJob.haversineMeters(40.001, -111.0, 40.002, -111.0));
        GpsSample sample = new GpsSample(
                "gps-01",
                "bench-a",
                Instant.parse("2026-05-25T22:57:46Z"),
                40.0015,
                -111.00002,
                50.0,
                180.0);

        TrackMatch match = TrackPositionDerivationJob.findBestMatch(sample, List.of(track), 75.0);

        assertNotNull(match);
        assertEquals(track, match.track);
        assertEquals(0.0, match.distanceMeters, 2.0);
        assertEquals(
                TrackPositionDerivationJob.haversineMeters(40.0, -111.0, 40.0015, -111.0),
                match.sMeters,
                2.5);
    }

    @Test
    void requireGpsSampleRejectsMissingLatitude() {
        Map<String, Object> envelope = Map.of(
                "device_id", "gps-01",
                "source_session", "bench-a",
                "captured_at", "2026-05-25T22:57:46Z",
                "payload", Map.of("longitude", -111.0));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> TrackPositionDerivationJob.requireGpsSample(envelope));

        assertEquals("payload.latitude must be present", error.getMessage());
    }
}
