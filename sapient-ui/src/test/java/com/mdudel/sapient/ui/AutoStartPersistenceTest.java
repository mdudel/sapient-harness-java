/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.gen.DetectionGenerator;
import com.mdudel.sapient.core.gen.SensorGenerator;
import com.mdudel.sapient.ui.persist.SavedDetectionConfig;
import com.mdudel.sapient.ui.persist.SavedSensorConfig;
import com.mdudel.sapient.ui.persist.SessionStore;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// NOTE: no protobuf on the sapient-ui test classpath, so we pass null for
// the StatusReport.System field and let SensorGenerator.Config coerce to
// SYSTEM_OK. See SessionStoreGeneratorConfigTest for the same pattern.

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the per-transmitter generator-config persistence added
 * 2026-08-06 for Marty's Auto-Start-ignores-saved-location bug.
 *
 * <p>Two axes:
 * <ul>
 *   <li>{@link AutoStartRecipe#sensorConfig(TransmittersPane.TxRow)} and
 *       {@code detectionConfig(TxRow)} prefer the row's persisted config
 *       over the hard-coded defaults when present (unit test, no FX).</li>
 *   <li>{@link TransmittersPane#snapshot()} and
 *       {@link TransmittersPane#restore(List)} round-trip the persisted
 *       configs so an edit survives a JVM restart (headless FX smoke).</li>
 * </ul>
 */
class AutoStartPersistenceTest {

    @BeforeAll
    static void bootFxToolkit() {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException already) {
            ready.countDown();
        }
        try { ready.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    // ── AutoStartRecipe row-based overloads ────────────────────────────

    @Test
    void sensorConfigUsesRowSavedValuesWhenPresent() {
        TransmittersPane.TxRow row = new TransmittersPane.TxRow(
                "tx", "127.0.0.1", 14000, "row-node-1");
        SavedSensorConfig sc = SavedSensorConfig.fromConfig(new SensorGenerator.Config(
                "row-node-1",
                500L,
                42.0, -71.0, 250.0,             // custom Boston-ish lat/lon
                false, 0.0, 0.0, 100.0,
                SensorGenerator.FovMode.POLYGON,
                180.0, 0.0, 10.0,
                0.0, 45.0, 0.0,
                8500.0, 30.0, 15.0,
                6,
                null,
                "surveillance",
                92,
                null));
        row.lastSensorCfg = sc;

        SensorGenerator.Config out = AutoStartRecipe.sensorConfig(row);
        // The row's saved values win.
        assertThat(out.centerLat).isEqualTo(42.0);
        assertThat(out.centerLon).isEqualTo(-71.0);
        assertThat(out.centerAltM).isEqualTo(250.0);
        assertThat(out.fovMode).isEqualTo(SensorGenerator.FovMode.POLYGON);
        assertThat(out.rangeMeters).isEqualTo(8500.0);
        // Even though POLYGON mode + moving=false override the recipe's
        // defaults, the nodeId still comes from the row (in case UUID was
        // regenerated after the config was saved).
        assertThat(out.nodeId).isEqualTo("row-node-1");
    }

    @Test
    void sensorConfigFallsBackToDefaultsWhenRowHasNoSavedCfg() {
        TransmittersPane.TxRow row = new TransmittersPane.TxRow(
                "tx", "127.0.0.1", 14000, "row-node-2");
        // row.lastSensorCfg deliberately null.
        SensorGenerator.Config out = AutoStartRecipe.sensorConfig(row);
        // Falls back to the hard-coded auto-start demo defaults.
        assertThat(out.moving).isTrue();
        assertThat(out.fovMode).isEqualTo(SensorGenerator.FovMode.CONE);
        assertThat(out.rangeMeters).isEqualTo(20_000.0);
        assertThat(out.nodeId).isEqualTo("row-node-2");
    }

    @Test
    void detectionConfigUsesRowSavedValuesWhenPresent() {
        TransmittersPane.TxRow row = new TransmittersPane.TxRow(
                "tx", "127.0.0.1", 14000, "row-node-3");
        row.lastDetectionCfg = SavedDetectionConfig.fromConfig(new DetectionGenerator.Config(
                "row-node-3",
                20,                          // more drones
                48.86, 2.35,                 // Paris
                5000.0,                       // tighter radius
                500L,                         // faster tick
                true, 8.0, 45.0,
                "person",                    // not "drone"
                0.6f,
                50.0, 5.0, 0.5, 10.0, 200.0));

        DetectionGenerator.Config out = AutoStartRecipe.detectionConfig(row);
        assertThat(out.trackCount).isEqualTo(20);
        assertThat(out.centerLat).isEqualTo(48.86);
        assertThat(out.classification).isEqualTo("person");
        assertThat(out.radiusMeters).isEqualTo(5000.0);
        assertThat(out.nodeId).isEqualTo("row-node-3");
    }

    @Test
    void detectionConfigFallsBackToDefaultsWhenRowHasNoSavedCfg() {
        TransmittersPane.TxRow row = new TransmittersPane.TxRow(
                "tx", "127.0.0.1", 14000, "row-node-4");
        DetectionGenerator.Config out = AutoStartRecipe.detectionConfig(row);
        assertThat(out.radiusMeters).isEqualTo(20_000.0);
        assertThat(out.classification).isEqualTo("drone");
        assertThat(out.moving).isTrue();
    }

    // ── TransmittersPane snapshot / restore round-trip ─────────────────

    @Test
    void snapshotAndRestoreCarryGeneratorConfigs() throws Exception {
        AtomicReference<TransmittersPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new TransmittersPane()));
        TransmittersPane pane = paneRef.get();

        // Add one transmitter with pre-populated saved configs.
        SavedSensorConfig s = SavedSensorConfig.fromConfig(new SensorGenerator.Config(
                "persist-node",
                800L,
                37.77, -122.42, 30.0,        // SF
                true, 4.0, 3.0, 500.0,
                SensorGenerator.FovMode.CONE,
                90.0, 15.0, 8.0,
                0.0, 40.0, 0.0,
                12_000.0, 40.0, 20.0,
                10,
                null,
                "scanning",
                75,
                0.2));
        SavedDetectionConfig d = SavedDetectionConfig.fromConfig(new DetectionGenerator.Config(
                "persist-node",
                8,
                37.77, -122.42,
                4000.0,
                750L,
                true, 12.0, 10.0,
                "vehicle",
                0.9f,
                80.0, 20.0, 0.5, 20.0, 300.0));
        SessionStore.SavedTransmitter incoming = new SessionStore.SavedTransmitter(
                "tx-persist", "10.0.0.9", 14099, "persist-node", true, s, d);

        runFx(() -> pane.restore(List.of(incoming)));

        // Snapshot must round-trip both configs.
        List<SessionStore.SavedTransmitter> snap = pane.snapshot();
        assertThat(snap).hasSize(1);
        SessionStore.SavedTransmitter got = snap.get(0);
        assertThat(got.name).isEqualTo("tx-persist");
        assertThat(got.enforceHandshake).isTrue();
        assertThat(got.sensorConfig).isNotNull();
        assertThat(got.sensorConfig.centerLat).isEqualTo(37.77);
        assertThat(got.sensorConfig.rangeMeters).isEqualTo(12_000.0);
        assertThat(got.sensorConfig.batteryDrainPerMin).isEqualTo(0.2);
        assertThat(got.detectionConfig).isNotNull();
        assertThat(got.detectionConfig.classification).isEqualTo("vehicle");
        assertThat(got.detectionConfig.radiusMeters).isEqualTo(4000.0);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static void runFx(Runnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try { r.run(); } catch (Throwable t) { err.set(t); }
            finally { done.countDown(); }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("FX task timed out");
        if (err.get() != null) throw new RuntimeException(err.get());
    }
}
