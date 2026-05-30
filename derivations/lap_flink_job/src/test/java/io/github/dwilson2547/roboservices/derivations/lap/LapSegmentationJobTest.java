package io.github.dwilson2547.roboservices.derivations.lap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dwilson2547.roboservices.derivations.lap.LapSegmentationJob.DeviceProfile;
import io.github.dwilson2547.roboservices.derivations.lap.LapSegmentationJob.LapCoProcessFunction;
import io.github.dwilson2547.roboservices.derivations.lap.LapSegmentationJob.ProfileResolver;
import io.github.dwilson2547.roboservices.derivations.lap.LapSegmentationJob.SensorFieldExtractor;
import io.github.dwilson2547.roboservices.derivations.lap.LapSegmentationJob.SessionStateData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LapSegmentationJobTest {

    // -------------------------------------------------------------------------
    // Haversine
    // -------------------------------------------------------------------------

    @Test
    void haversineReturnsZeroForIdenticalPoints() {
        assertEquals(0.0, LapSegmentationJob.haversineMeters(37.0, -112.0, 37.0, -112.0), 0.001);
    }

    @Test
    void haversineReturnsApproximatelyCorrectDistance() {
        // Roughly 111.2 km per degree of latitude
        double dist = LapSegmentationJob.haversineMeters(37.0, -112.0, 38.0, -112.0);
        assertEquals(111_200, dist, 500);
    }

    @Test
    void haversineShortDistanceWithinTrackScale() {
        // ~56 m south along the same longitude
        double dist = LapSegmentationJob.haversineMeters(37.0000, -112.0, 36.9995, -112.0);
        assertEquals(55.6, dist, 2.0);
    }

    // -------------------------------------------------------------------------
    // Bearing
    // -------------------------------------------------------------------------

    @Test
    void bearingDueNorth() {
        double bearing = LapSegmentationJob.bearingDeg(37.0, -112.0, 38.0, -112.0);
        assertEquals(0.0, bearing, 0.5);
    }

    @Test
    void bearingDueSouth() {
        double bearing = LapSegmentationJob.bearingDeg(38.0, -112.0, 37.0, -112.0);
        assertEquals(180.0, bearing, 0.5);
    }

    @Test
    void bearingDeltaHandlesWraparound() {
        assertEquals(2.0, LapSegmentationJob.bearingDelta(1.0, 359.0), 0.001);
        assertEquals(10.0, LapSegmentationJob.bearingDelta(5.0, 355.0), 0.001);
    }

    @Test
    void bearingDeltaSymmetric() {
        assertEquals(
                LapSegmentationJob.bearingDelta(90.0, 120.0),
                LapSegmentationJob.bearingDelta(120.0, 90.0),
                0.001);
    }

    // -------------------------------------------------------------------------
    // SensorFieldExtractor
    // -------------------------------------------------------------------------

    @Test
    void extractDoubleTopLevelField() {
        Map<String, Object> map = Map.of("ground_speed_kph", 85.5);
        assertEquals(85.5, SensorFieldExtractor.extractDouble(map, "ground_speed_kph"), 0.001);
    }

    @Test
    void extractDoubleNestedField() {
        Map<String, Object> inner = Map.of("x", 1.23, "y", 4.56, "z", 7.89);
        Map<String, Object> outer = Map.of("accel_m_s2", inner);
        assertEquals(1.23, SensorFieldExtractor.extractDouble(outer, "accel_m_s2.x"), 0.001);
        assertEquals(7.89, SensorFieldExtractor.extractDouble(outer, "accel_m_s2.z"), 0.001);
    }

    @Test
    void extractDoubleReturnsNullWhenFieldMissing() {
        Map<String, Object> map = Map.of("other_field", 1.0);
        assertNull(SensorFieldExtractor.extractDouble(map, "ground_speed_kph"));
    }

    @Test
    void extractDoubleReturnsNullWhenNestedPathBroken() {
        Map<String, Object> map = Map.of("accel_m_s2", "not-a-map");
        assertNull(SensorFieldExtractor.extractDouble(map, "accel_m_s2.x"));
    }

    @Test
    void accelMagnitudeComputesCorrectly() {
        Map<String, Object> inner = Map.of("x", 3.0, "y", 4.0, "z", 0.0);
        Map<String, Object> payload = Map.of("accel_m_s2", inner);
        // sqrt(9+16+0) = 5
        assertEquals(5.0, SensorFieldExtractor.accelMagnitude(
                payload, List.of("accel_m_s2.x", "accel_m_s2.y", "accel_m_s2.z")), 0.001);
    }

    @Test
    void accelMagnitudeReturnsNaNWhenFieldMissing() {
        Map<String, Object> payload = Map.of();
        assertTrue(Double.isNaN(SensorFieldExtractor.accelMagnitude(
                payload, List.of("accel_m_s2.x", "accel_m_s2.y", "accel_m_s2.z"))));
    }

    // -------------------------------------------------------------------------
    // ProfileResolver
    // -------------------------------------------------------------------------

    @Test
    void profileResolverUsesDefaultWhenNoProfilesJson() {
        ProfileResolver resolver = new ProfileResolver("", null, 0L);
        DeviceProfile p = resolver.resolve("SOME-DEVICE");
        assertNotNull(p);
        assertEquals("scraps-v1", p.profileId);
    }

    @Test
    void profileResolverMatchesByPrefix() {
        String json = """
                [
                  {"profile_id":"mid-tier-v1","device_id_prefix":"MID","geofence_radius_m":35.0},
                  {"profile_id":"scraps-v1","device_id_prefix":"SCRAPS","geofence_radius_m":40.0}
                ]
                """;
        ProfileResolver resolver = new ProfileResolver(json, null, 0L);
        assertEquals("scraps-v1", resolver.resolve("SCRAPS-001").profileId);
        assertEquals("mid-tier-v1", resolver.resolve("MID-TIER-001").profileId);
    }

    @Test
    void profileResolverFallsBackToFirstProfileWhenNoMatch() {
        String json = """
                [{"profile_id":"scraps-v1","device_id_prefix":"SCRAPS"}]
                """;
        ProfileResolver resolver = new ProfileResolver(json, null, 0L);
        DeviceProfile p = resolver.resolve("UNKNOWN-999");
        assertEquals("scraps-v1", p.profileId);
    }

    // -------------------------------------------------------------------------
    // Launch detection
    // -------------------------------------------------------------------------

    @Test
    void launchNotDetectedWhenSpeedBelowFloor() {
        SessionStateData state = new SessionStateData();
        DeviceProfile profile = new DeviceProfile();
        assertFalse(LapCoProcessFunction.isLaunchDetected(state, profile, 2.0, 1000L));
    }

    @Test
    void launchDetectedWithNoImuWhenSpeedMeetsFloor() {
        SessionStateData state = new SessionStateData();
        DeviceProfile profile = new DeviceProfile();
        profile.imu = null;
        assertTrue(LapCoProcessFunction.isLaunchDetected(state, profile, 5.0, 1000L));
    }

    @Test
    void launchNotDetectedWhenImuStale() {
        SessionStateData state = new SessionStateData();
        state.lastImuMagnitude = 15.0;
        state.lastImuTimestampMs = 0L;
        DeviceProfile profile = new DeviceProfile();
        profile.imu = new DeviceProfile.ImuConfig();
        // eventTimeMs = 5000, staleness = 2000 -> stale
        assertFalse(LapCoProcessFunction.isLaunchDetected(state, profile, 5.0, 5000L));
    }

    @Test
    void launchDetectedWhenRawImuBaselineCalibrated() {
        SessionStateData state = new SessionStateData();
        // Simulate 10 baseline readings averaging 9.8 m/s² (gravity at rest)
        state.imuBaselineSum = 98.0;
        state.imuBaselineCount = 10;
        // Current magnitude = 14.3 -> deviation = 4.5, above threshold 2.5
        state.lastImuMagnitude = 14.3;
        state.lastImuTimestampMs = 1000L;

        DeviceProfile profile = new DeviceProfile();
        profile.imu = new DeviceProfile.ImuConfig();
        profile.imu.gravityCompensated = false;

        assertTrue(LapCoProcessFunction.isLaunchDetected(state, profile, 5.0, 1500L));
    }

    @Test
    void launchNotDetectedWhenRawImuBaselineNotYetCalibrated() {
        SessionStateData state = new SessionStateData();
        state.imuBaselineCount = 5; // not enough yet (default 10)
        state.lastImuMagnitude = 15.0;
        state.lastImuTimestampMs = 1000L;

        DeviceProfile profile = new DeviceProfile();
        profile.imu = new DeviceProfile.ImuConfig();
        profile.imu.gravityCompensated = false;

        assertFalse(LapCoProcessFunction.isLaunchDetected(state, profile, 5.0, 1500L));
    }

    @Test
    void launchDetectedWhenGravityCompensatedImuExceedsThreshold() {
        SessionStateData state = new SessionStateData();
        state.lastImuMagnitude = 3.5; // > 2.5 threshold
        state.lastImuTimestampMs = 1000L;

        DeviceProfile profile = new DeviceProfile();
        profile.imu = new DeviceProfile.ImuConfig();
        profile.imu.gravityCompensated = true;

        assertTrue(LapCoProcessFunction.isLaunchDetected(state, profile, 5.0, 1500L));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> gpsEnvelope(String deviceId, String session, double lat, double lon,
            double speedKph, String capturedAt, String messageType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("latitude", lat);
        payload.put("longitude", lon);
        payload.put("ground_speed_kph", speedKph);
        payload.put("message_type", messageType);

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("device_id", deviceId);
        envelope.put("source_session", session);
        envelope.put("captured_at", capturedAt);
        envelope.put("message_type", messageType);
        envelope.put("payload", payload);
        return envelope;
    }
}
