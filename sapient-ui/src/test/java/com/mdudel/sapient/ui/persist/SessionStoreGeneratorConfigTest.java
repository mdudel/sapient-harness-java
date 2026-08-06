/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.persist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdudel.sapient.core.gen.DetectionGenerator;
import com.mdudel.sapient.core.gen.SensorGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// NOTE: intentionally NO import of uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport
// here — sapient-ui doesn't have protobuf-java on its test classpath. We test
// the enum-name-as-String round-trip via raw JSON, and let the generator
// Config constructors coerce a null enum arg into the canonical default.

/**
 * Round-trip + back-compat coverage for the per-transmitter generator
 * configs added 2026-08-06. Ensures that:
 * <ul>
 *   <li>legacy session.json files (no sensorConfig / detectionConfig fields)
 *       still deserialise with both fields null;</li>
 *   <li>a fresh SavedSensorConfig / SavedDetectionConfig round-trips through
 *       Jackson with every field preserved;</li>
 *   <li>from{@link SensorGenerator.Config}(...) &rarr; to{@link SensorGenerator.Config}(nodeId)
 *       is byte-equivalent for every scalar field.</li>
 * </ul>
 */
class SessionStoreGeneratorConfigTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── SavedTransmitter back-compat ───────────────────────────────────

    @Test
    void legacyTransmitterJsonHasNullConfigs() throws Exception {
        String legacy = "{\"name\":\"tx-A\",\"host\":\"127.0.0.1\",\"port\":14000,"
                + "\"nodeId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"enforceHandshake\":true}";
        SessionStore.SavedTransmitter t = JSON.readValue(legacy, SessionStore.SavedTransmitter.class);
        assertThat(t.sensorConfig).isNull();
        assertThat(t.detectionConfig).isNull();
        assertThat(t.enforceHandshake).isTrue();
    }

    @Test
    void fiveArgTransmitterConstructorLeavesConfigsNull() {
        SessionStore.SavedTransmitter t = new SessionStore.SavedTransmitter(
                "tx", "h", 14000, "44444444-4444-4444-4444-444444444444", true);
        assertThat(t.sensorConfig).isNull();
        assertThat(t.detectionConfig).isNull();
    }

    // ── SavedSensorConfig round-trip ───────────────────────────────────

    @Test
    void sensorConfigFromToRoundTripsEveryField() {
        // system=null lets the SensorGenerator.Config constructor stamp the
        // canonical default (SYSTEM_OK) without us naming the enum directly
        // (see file-top comment: no protobuf on the sapient-ui test cp).
        SensorGenerator.Config in = new SensorGenerator.Config(
                "node-1",
                500L,
                52.5, 13.4, 45.0,           // pos
                true, 3.0, 7.0, 1500.0,     // motion
                SensorGenerator.FovMode.POLYGON_WITH_OBSCURATION,
                90.0, -20.0, 10.0,          // az / rate / init el
                -5.0, 45.0, 2.0,            // el min / max / rate
                12500.0, 60.0, 25.0,        // range / h ext / v ext
                12,                          // polygon verts
                null,                        // system (Config coerces to SYSTEM_OK)
                "surveillance",
                80,                          // battery init
                0.5);                        // drain
        SavedSensorConfig dto = SavedSensorConfig.fromConfig(in);
        SensorGenerator.Config out = dto.toConfig("node-1");

        // Every scalar field survives (compare via property so we don't
        // need the enum value on the test classpath).
        assertThat(out.nodeId).isEqualTo("node-1");
        assertThat(out.tickMs).isEqualTo(in.tickMs);
        assertThat(out.centerLat).isEqualTo(in.centerLat);
        assertThat(out.centerLon).isEqualTo(in.centerLon);
        assertThat(out.centerAltM).isEqualTo(in.centerAltM);
        assertThat(out.moving).isEqualTo(in.moving);
        assertThat(out.speedMps).isEqualTo(in.speedMps);
        assertThat(out.turnJitterDeg).isEqualTo(in.turnJitterDeg);
        assertThat(out.motionRadiusMeters).isEqualTo(in.motionRadiusMeters);
        assertThat(out.fovMode).isEqualTo(in.fovMode);
        assertThat(out.initialAzimuthDeg).isEqualTo(in.initialAzimuthDeg);
        assertThat(out.azimuthRateDegPerSec).isEqualTo(in.azimuthRateDegPerSec);
        assertThat(out.initialElevationDeg).isEqualTo(in.initialElevationDeg);
        assertThat(out.elevationMinDeg).isEqualTo(in.elevationMinDeg);
        assertThat(out.elevationMaxDeg).isEqualTo(in.elevationMaxDeg);
        assertThat(out.elevationRateDegPerSec).isEqualTo(in.elevationRateDegPerSec);
        assertThat(out.rangeMeters).isEqualTo(in.rangeMeters);
        assertThat(out.horizontalExtentDeg).isEqualTo(in.horizontalExtentDeg);
        assertThat(out.verticalExtentDeg).isEqualTo(in.verticalExtentDeg);
        assertThat(out.polygonVertexCount).isEqualTo(in.polygonVertexCount);
        assertThat(out.mode).isEqualTo(in.mode);
        assertThat(out.initialBatteryLevel).isEqualTo(in.initialBatteryLevel);
        assertThat(out.batteryDrainPerMin).isEqualTo(in.batteryDrainPerMin);
        // System enum name survives via the DTO's String storage.
        assertThat(dto.system).isEqualTo("SYSTEM_OK");
    }

    @Test
    void sensorConfigJsonRoundTripsThroughJackson() throws Exception {
        SensorGenerator.Config in = new SensorGenerator.Config(
                "node-jj",
                750L,
                51.5, -0.1, 12.0,
                false, 0.0, 0.0, 100.0,
                SensorGenerator.FovMode.CONE,
                45.0, 15.0, 3.0,
                -1.0, 15.0, 0.0,
                8000.0, 20.0, 10.0,
                6,
                null,
                "watchmode",
                92,
                null);
        SavedSensorConfig dto = SavedSensorConfig.fromConfig(in);
        String json = JSON.writeValueAsString(dto);
        SavedSensorConfig round = JSON.readValue(json, SavedSensorConfig.class);
        SensorGenerator.Config out = round.toConfig("node-jj");

        assertThat(out.centerLat).isEqualTo(51.5);
        assertThat(out.mode).isEqualTo("watchmode");
        assertThat(out.batteryDrainPerMin).isNull();
        assertThat(out.fovMode).isEqualTo(SensorGenerator.FovMode.CONE);
        assertThat(round.system).isEqualTo("SYSTEM_OK");
    }

    @Test
    void partialSensorConfigStillYieldsRunnableConfig() throws Exception {
        // Simulate a partial blob (e.g. an intermediate schema): only lat + lon.
        String partial = "{\"centerLat\":42.0,\"centerLon\":-71.0}";
        SavedSensorConfig dto = JSON.readValue(partial, SavedSensorConfig.class);
        SensorGenerator.Config out = dto.toConfig("partial-node");
        // Explicit fields survive.
        assertThat(out.centerLat).isEqualTo(42.0);
        assertThat(out.centerLon).isEqualTo(-71.0);
        // Missing fields fall through to defaults.
        assertThat(out.tickMs).isEqualTo(1000L);
        assertThat(out.fovMode).isEqualTo(SensorGenerator.FovMode.CONE);
        assertThat(out.mode).isEqualTo("scanning");
    }

    @Test
    void unknownFovModeFallsBackToCone() throws Exception {
        String weird = "{\"fovMode\":\"HYPERCONE\",\"centerLat\":1.0,\"centerLon\":2.0}";
        SavedSensorConfig dto = JSON.readValue(weird, SavedSensorConfig.class);
        assertThat(dto.toConfig("n").fovMode).isEqualTo(SensorGenerator.FovMode.CONE);
    }

    @Test
    void unknownSystemFallsBackToSystemOk() throws Exception {
        // Unknown system name should quietly fall through to SYSTEM_OK in
        // toConfig(). We can't touch the protobuf enum type from this test
        // (no proto on cp), so we prove it by round-tripping the Config back
        // through the DTO — fromConfig() reads config.system.name() which
        // gives us the canonical String without importing the enum class.
        String weird = "{\"system\":\"SYSTEM_ON_FIRE\"}";
        SavedSensorConfig dto = JSON.readValue(weird, SavedSensorConfig.class);
        SavedSensorConfig echoed = SavedSensorConfig.fromConfig(dto.toConfig("n"));
        assertThat(echoed.system).isEqualTo("SYSTEM_OK");
    }

    // ── SavedDetectionConfig round-trip ────────────────────────────────

    @Test
    void detectionConfigFromToRoundTripsEveryField() {
        DetectionGenerator.Config in = new DetectionGenerator.Config(
                "det-1",
                12,
                40.7, -74.0,
                7500.0,
                750L,
                true, 22.5, 12.0,
                "vehicle",
                0.72f,
                200.0, 60.0, 2.5, 50.0, 800.0);
        SavedDetectionConfig dto = SavedDetectionConfig.fromConfig(in);
        DetectionGenerator.Config out = dto.toConfig("det-1");

        assertThat(out.nodeId).isEqualTo("det-1");
        assertThat(out.trackCount).isEqualTo(in.trackCount);
        assertThat(out.centerLat).isEqualTo(in.centerLat);
        assertThat(out.centerLon).isEqualTo(in.centerLon);
        assertThat(out.radiusMeters).isEqualTo(in.radiusMeters);
        assertThat(out.tickMs).isEqualTo(in.tickMs);
        assertThat(out.moving).isEqualTo(in.moving);
        assertThat(out.speedMps).isEqualTo(in.speedMps);
        assertThat(out.turnJitterDeg).isEqualTo(in.turnJitterDeg);
        assertThat(out.classification).isEqualTo(in.classification);
        assertThat(out.confidence).isEqualTo(in.confidence);
        assertThat(out.initialAltitudeM).isEqualTo(in.initialAltitudeM);
        assertThat(out.altitudeJitterM).isEqualTo(in.altitudeJitterM);
        assertThat(out.verticalRateMps).isEqualTo(in.verticalRateMps);
        assertThat(out.minAltitudeM).isEqualTo(in.minAltitudeM);
        assertThat(out.maxAltitudeM).isEqualTo(in.maxAltitudeM);
    }

    @Test
    void detectionConfigJsonRoundTripsThroughJackson() throws Exception {
        DetectionGenerator.Config in = new DetectionGenerator.Config(
                "det-jj",
                3,
                48.86, 2.35,
                1200.0,
                333L,
                false, 0.5, 5.0,
                "person",
                0.99f,
                1.5, 0.0, 0.0, 0.0, 0.0);
        String json = JSON.writeValueAsString(SavedDetectionConfig.fromConfig(in));
        SavedDetectionConfig round = JSON.readValue(json, SavedDetectionConfig.class);
        DetectionGenerator.Config out = round.toConfig("det-jj");

        assertThat(out.trackCount).isEqualTo(3);
        assertThat(out.classification).isEqualTo("person");
        assertThat(out.confidence).isEqualTo(0.99f);
        assertThat(out.moving).isFalse();
    }

    @Test
    void partialDetectionConfigStillYieldsRunnableConfig() throws Exception {
        String partial = "{\"trackCount\":42,\"classification\":\"bird\"}";
        SavedDetectionConfig dto = JSON.readValue(partial, SavedDetectionConfig.class);
        DetectionGenerator.Config out = dto.toConfig("partial");
        assertThat(out.trackCount).isEqualTo(42);
        assertThat(out.classification).isEqualTo("bird");
        // Defaults fill the gaps.
        assertThat(out.tickMs).isEqualTo(1000L);
        assertThat(out.confidence).isEqualTo(0.85f);
        assertThat(out.radiusMeters).isEqualTo(20_000.0);
    }

    // ── Full transmitter round-trip via the whole SessionStore payload ─

    @Test
    void savedTransmitterRoundTripsCarriesBothConfigs() throws Exception {
        SavedSensorConfig s = SavedSensorConfig.fromConfig(new SensorGenerator.Config(
                "n1",
                1000L,
                52.5, 13.4, 100.0,
                true, 5.0, 5.0, 2000.0,
                SensorGenerator.FovMode.CONE,
                0.0, 30.0, 5.0,
                0.0, 30.0, 0.0,
                20_000.0, 30.0, 15.0,
                8,
                null,
                "scanning",
                100,
                null));
        SavedDetectionConfig d = SavedDetectionConfig.fromConfig(new DetectionGenerator.Config(
                "n1",
                5,
                52.5, 13.4,
                20_000.0,
                1000L,
                true, 15.0, 15.0,
                "drone",
                0.85f,
                150.0, 50.0, 0.0, 100.0, 500.0));

        SessionStore.SavedTransmitter in = new SessionStore.SavedTransmitter(
                "tx-full", "10.0.0.5", 14022, "aaaa-node", true, s, d);
        String json = JSON.writeValueAsString(in);
        SessionStore.SavedTransmitter out = JSON.readValue(json, SessionStore.SavedTransmitter.class);

        assertThat(out.sensorConfig).isNotNull();
        assertThat(out.detectionConfig).isNotNull();
        assertThat(out.sensorConfig.centerLat).isEqualTo(52.5);
        assertThat(out.sensorConfig.fovMode).isEqualTo("CONE");
        assertThat(out.detectionConfig.classification).isEqualTo("drone");
        assertThat(out.detectionConfig.radiusMeters).isEqualTo(20_000.0);
    }
}
