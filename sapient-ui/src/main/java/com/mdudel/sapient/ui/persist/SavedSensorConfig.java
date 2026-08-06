/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.persist;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mdudel.sapient.core.gen.SensorGenerator;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/**
 * On-disk mirror of {@link SensorGenerator.Config}.
 *
 * <p>Every configurable field of the live generator config is a public
 * mutable field here so Jackson can round-trip the whole thing with zero
 * annotations. Enum-typed fields are stored as {@link String} so a future
 * proto rename doesn't kill an old session.json (the {@link #toConfig(String)}
 * helper coerces unknown names back to the class-level defaults, and the
 * {@link #fromConfig(SensorGenerator.Config)} helper only writes canonical
 * spellings).
 *
 * <p>The {@code nodeId} is deliberately NOT stored here — the transmitter's
 * nodeId lives on {@link SessionStore.SavedTransmitter#nodeId} and can be
 * regenerated in the Edit dialog. {@link #toConfig(String)} takes the current
 * nodeId as a parameter so the persisted config always tracks the row's
 * current identity even if the operator regenerates the UUID.
 *
 * <p>Backward-compat rules:
 * <ul>
 *   <li>All fields are boxed / nullable so a partial JSON blob from an
 *       older schema still deserialises.</li>
 *   <li>{@link #toConfig(String)} substitutes hard-coded generator-side
 *       defaults for any {@code null} field so a partial blob still yields
 *       a runnable {@link SensorGenerator.Config}.</li>
 * </ul>
 */
public final class SavedSensorConfig {

    // ---- Cadence ----
    public Long   tickMs;

    // ---- Platform position ----
    public Double centerLat;
    public Double centerLon;
    public Double centerAltM;

    // ---- Motion ----
    public Boolean moving;
    public Double  speedMps;
    public Double  turnJitterDeg;
    public Double  motionRadiusMeters;

    // ---- FOV geometry ----
    /** {@link SensorGenerator.FovMode} name; unknown values fall back to CONE. */
    public String  fovMode;
    public Double  initialAzimuthDeg;
    public Double  azimuthRateDegPerSec;
    public Double  initialElevationDeg;
    public Double  elevationMinDeg;
    public Double  elevationMaxDeg;
    public Double  elevationRateDegPerSec;
    public Double  rangeMeters;
    public Double  horizontalExtentDeg;
    public Double  verticalExtentDeg;
    public Integer polygonVertexCount;

    // ---- Status housekeeping ----
    /** {@link StatusReport.System} enum name; unknown values fall back to SYSTEM_OK. */
    public String  system;
    public String  mode;
    public Integer initialBatteryLevel;
    /** {@code null} = no drain. */
    public Double  batteryDrainPerMin;

    public SavedSensorConfig() {
        // Jackson no-arg
    }

    /**
     * Snapshot an existing {@link SensorGenerator.Config} into a persistable
     * DTO. Called at the point the user clicks OK in the sensor-generator
     * dialog (or in a future Edit-Sensor-Config path).
     */
    public static SavedSensorConfig fromConfig(SensorGenerator.Config c) {
        if (c == null) return null;
        SavedSensorConfig s = new SavedSensorConfig();
        s.tickMs                 = c.tickMs;
        s.centerLat              = c.centerLat;
        s.centerLon              = c.centerLon;
        s.centerAltM             = c.centerAltM;
        s.moving                 = c.moving;
        s.speedMps               = c.speedMps;
        s.turnJitterDeg          = c.turnJitterDeg;
        s.motionRadiusMeters     = c.motionRadiusMeters;
        s.fovMode                = c.fovMode == null ? null : c.fovMode.name();
        s.initialAzimuthDeg      = c.initialAzimuthDeg;
        s.azimuthRateDegPerSec   = c.azimuthRateDegPerSec;
        s.initialElevationDeg    = c.initialElevationDeg;
        s.elevationMinDeg        = c.elevationMinDeg;
        s.elevationMaxDeg        = c.elevationMaxDeg;
        s.elevationRateDegPerSec = c.elevationRateDegPerSec;
        s.rangeMeters            = c.rangeMeters;
        s.horizontalExtentDeg    = c.horizontalExtentDeg;
        s.verticalExtentDeg      = c.verticalExtentDeg;
        s.polygonVertexCount     = c.polygonVertexCount;
        s.system                 = c.system == null ? null : c.system.name();
        s.mode                   = c.mode;
        s.initialBatteryLevel    = c.initialBatteryLevel;
        s.batteryDrainPerMin     = c.batteryDrainPerMin;
        return s;
    }

    /**
     * Reconstruct a {@link SensorGenerator.Config} for the given
     * {@code nodeId}. Any {@code null} field is replaced with the same
     * hard-coded default the {@link AutoStartRecipe} / {@code MessageDialogs}
     * layer picks, so a partial persisted blob still yields a runnable config.
     *
     * <p>Not called {@code toConfig()} without a nodeId argument on purpose:
     * the nodeId is authoritative on the row, not on the persisted config —
     * see class-level javadoc.
     */
    @JsonIgnore
    public SensorGenerator.Config toConfig(String nodeId) {
        SensorGenerator.FovMode fov = SensorGenerator.FovMode.CONE;
        if (fovMode != null) {
            try { fov = SensorGenerator.FovMode.valueOf(fovMode); }
            catch (IllegalArgumentException ignored) { /* fall back to CONE */ }
        }
        StatusReport.System sys = StatusReport.System.SYSTEM_OK;
        if (system != null) {
            try { sys = StatusReport.System.valueOf(system); }
            catch (IllegalArgumentException ignored) { /* fall back to SYSTEM_OK */ }
        }
        return new SensorGenerator.Config(
                nodeId,
                orDefault(tickMs, 1000L),
                orDefault(centerLat, 50.0782),
                orDefault(centerLon, 8.2398),
                orDefault(centerAltM, 100.0),
                orDefault(moving, false),
                orDefault(speedMps, 5.0),
                orDefault(turnJitterDeg, 5.0),
                orDefault(motionRadiusMeters, 2000.0),
                fov,
                orDefault(initialAzimuthDeg, 0.0),
                orDefault(azimuthRateDegPerSec, 30.0),
                orDefault(initialElevationDeg, 5.0),
                orDefault(elevationMinDeg, 0.0),
                orDefault(elevationMaxDeg, 30.0),
                orDefault(elevationRateDegPerSec, 0.0),
                orDefault(rangeMeters, 5000.0),
                orDefault(horizontalExtentDeg, 30.0),
                orDefault(verticalExtentDeg, 15.0),
                orDefault(polygonVertexCount, 8),
                sys,
                mode == null || mode.isBlank() ? "scanning" : mode,
                orDefault(initialBatteryLevel, 100),
                batteryDrainPerMin);
    }

    private static long   orDefault(Long v, long d)         { return v == null ? d : v; }
    private static int    orDefault(Integer v, int d)       { return v == null ? d : v; }
    private static double orDefault(Double v, double d)     { return v == null ? d : v; }
    private static boolean orDefault(Boolean v, boolean d)  { return v == null ? d : v; }
}
