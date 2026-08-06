/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.gen.DetectionGenerator;
import com.mdudel.sapient.core.gen.SensorGenerator;
import com.mdudel.sapient.ui.dialog.MessageDialogs;
import com.mdudel.sapient.ui.persist.SavedDetectionConfig;
import com.mdudel.sapient.ui.persist.SavedSensorConfig;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/**
 * Pre-canned Config bundles for the "Auto-Start (Register + Sensor +
 * Detections)" flow.
 *
 * <p>The whole point of this class is to be a single-source-of-truth for
 * the sane demo defaults we want the auto-start dropdown to use, so the
 * choreography code in {@link TransmittersPane} stays readable and the
 * defaults are reviewable in one place.
 *
 * <p>Values chosen to match Marty's 2026-08-04 14:18 UTC ask:
 * <ul>
 *   <li>sensor status: <b>moving platform</b>, <b>CONE FOV</b>, other
 *       fields = {@link MessageDialogs} defaults.</li>
 *   <li>detections: <b>range 20 000 m</b> (spawn radius), classification
 *       <b>"drone"</b>, motion on, other fields = {@link MessageDialogs}
 *       defaults.</li>
 * </ul>
 */
public final class AutoStartRecipe {

    private AutoStartRecipe() {
        // utility
    }

    /**
     * Build a {@link SensorGenerator.Config} for the auto-start cascade,
     * preferring the transmitter's persisted per-row config when present.
     *
     * <p>This is the entry point the {@code TransmittersPane} auto-start
     * flow calls. If the operator has customised the sensor config for
     * this transmitter (via the Start Sensor Generator dialog, whose OK
     * button stashes the resulting config back onto the row), those values
     * win. Otherwise, the built-in demo defaults apply. Fixes Marty
     * 2026-08-06 09:59 UTC — Auto-Start now honours edits.
     *
     * @param row transmitter row — provides both the current {@code nodeId}
     *            (in case the operator regenerated the UUID) and the
     *            persisted {@code lastSensorCfg}.
     */
    public static SensorGenerator.Config sensorConfig(TransmittersPane.TxRow row) {
        if (row != null && row.lastSensorCfg != null) {
            return row.lastSensorCfg.toConfig(row.nodeId);
        }
        String nodeId = row == null ? "" : row.nodeId;
        return sensorConfig(nodeId);
    }

    /**
     * Legacy nodeId-only overload. Returns the hard-coded demo defaults
     * with the given nodeId stamped in. Preserved for the existing unit
     * tests and for the {@link #sensorConfig(TransmittersPane.TxRow)}
     * fallback path when no persisted config exists.
     */
    public static SensorGenerator.Config sensorConfig(String nodeId) {
        return new SensorGenerator.Config(
                nodeId,
                /* tickMs                */ 1000,
                /* centerLat             */ MessageDialogs.DEFAULT_LAT,
                /* centerLon             */ MessageDialogs.DEFAULT_LON,
                /* centerAltM            */ 100.0,
                /* moving                */ true,   // per ask
                /* speedMps              */ 5.0,
                /* turnJitterDeg         */ 5,
                /* motionRadiusMeters    */ 2000,
                /* fovMode               */ SensorGenerator.FovMode.CONE,  // per ask
                /* initialAzimuthDeg     */ 0.0,
                /* azimuthRateDegPerSec  */ 30.0,   // ~5 RPM sweep
                /* initialElevationDeg   */ 5.0,
                /* elevationMinDeg       */ 0.0,
                /* elevationMaxDeg       */ 30.0,
                /* elevationRateDegPerSec*/ 0.0,
                /* rangeMeters           */ 20_000.0,  // matches detection radius
                /* horizontalExtentDeg   */ 30.0,
                /* verticalExtentDeg     */ 15.0,
                /* polygonVertexCount    */ 8,
                /* system                */ StatusReport.System.SYSTEM_OK,
                /* mode                  */ "scanning",
                /* initialBatteryLevel   */ 100,
                /* batteryDrainPerMin    */ null);
    }

    /**
     * Build a {@link DetectionGenerator.Config} for the auto-start cascade,
     * preferring the transmitter's persisted per-row config when present.
     * Sibling of {@link #sensorConfig(TransmittersPane.TxRow)} — same
     * contract, same fix context.
     */
    public static DetectionGenerator.Config detectionConfig(TransmittersPane.TxRow row) {
        if (row != null && row.lastDetectionCfg != null) {
            return row.lastDetectionCfg.toConfig(row.nodeId);
        }
        String nodeId = row == null ? "" : row.nodeId;
        return detectionConfig(nodeId);
    }

    /**
     * Legacy nodeId-only overload. See
     * {@link #sensorConfig(String)} for the fallback contract.
     */
    public static DetectionGenerator.Config detectionConfig(String nodeId) {
        return new DetectionGenerator.Config(
                nodeId,
                /* trackCount        */ 5,
                /* centerLat         */ MessageDialogs.DEFAULT_LAT,
                /* centerLon         */ MessageDialogs.DEFAULT_LON,
                /* radiusMeters      */ 20_000.0,   // per ask
                /* tickMs            */ 1000,
                /* moving            */ true,
                /* speedMps          */ 15.0,       // ~55 km/h, typical drone speed
                /* turnJitterDeg     */ 15,
                /* classification    */ "drone",    // per ask
                /* confidence        */ 0.85f,
                /* initialAltitudeM  */ 150.0,
                /* altitudeJitterM   */ 50.0,
                /* verticalRateMps   */ 0.0,
                /* minAltitudeM      */ 100.0,
                /* maxAltitudeM      */ 500.0);
    }
}
