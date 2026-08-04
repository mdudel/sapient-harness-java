/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.gen.DetectionGenerator;
import com.mdudel.sapient.core.gen.SensorGenerator;
import com.mdudel.sapient.ui.dialog.MessageDialogs;
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
     * Build a {@link SensorGenerator.Config} suitable for the auto-start
     * cascade. Mirrors the sensor generator dialog's defaults but forces
     * {@code moving=true} and {@code fovMode=CONE}.
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
     * Build a {@link DetectionGenerator.Config} suitable for the auto-start
     * cascade. Mirrors the detection generator dialog's defaults but forces
     * {@code radiusMeters=20000} and {@code classification="drone"}.
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
