/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.persist;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mdudel.sapient.core.gen.DetectionGenerator;

/**
 * On-disk mirror of {@link DetectionGenerator.Config}. See
 * {@link SavedSensorConfig} for the design contract — this class follows
 * exactly the same pattern (public nullable fields, {@code fromConfig} /
 * {@code toConfig(nodeId)} helpers, defaults substituted on load).
 *
 * <p>The {@code nodeId} is not stored here — see
 * {@link SavedSensorConfig} class-level javadoc for the reasoning.
 */
public final class SavedDetectionConfig {

    public Integer trackCount;

    public Double  centerLat;
    public Double  centerLon;
    public Double  radiusMeters;

    public Long    tickMs;

    public Boolean moving;
    public Double  speedMps;
    public Double  turnJitterDeg;

    public String  classification;
    public Float   confidence;

    // Altitude / vertical motion
    public Double  initialAltitudeM;
    public Double  altitudeJitterM;
    public Double  verticalRateMps;
    public Double  minAltitudeM;
    public Double  maxAltitudeM;

    public SavedDetectionConfig() {
        // Jackson no-arg
    }

    public static SavedDetectionConfig fromConfig(DetectionGenerator.Config c) {
        if (c == null) return null;
        SavedDetectionConfig s = new SavedDetectionConfig();
        s.trackCount       = c.trackCount;
        s.centerLat        = c.centerLat;
        s.centerLon        = c.centerLon;
        s.radiusMeters     = c.radiusMeters;
        s.tickMs           = c.tickMs;
        s.moving           = c.moving;
        s.speedMps         = c.speedMps;
        s.turnJitterDeg    = c.turnJitterDeg;
        s.classification   = c.classification;
        s.confidence       = c.confidence;
        s.initialAltitudeM = c.initialAltitudeM;
        s.altitudeJitterM  = c.altitudeJitterM;
        s.verticalRateMps  = c.verticalRateMps;
        s.minAltitudeM     = c.minAltitudeM;
        s.maxAltitudeM     = c.maxAltitudeM;
        return s;
    }

    @JsonIgnore
    public DetectionGenerator.Config toConfig(String nodeId) {
        return new DetectionGenerator.Config(
                nodeId,
                orDefault(trackCount, 5),
                orDefault(centerLat, 50.0782),
                orDefault(centerLon, 8.2398),
                orDefault(radiusMeters, 20_000.0),
                orDefault(tickMs, 1000L),
                orDefault(moving, true),
                orDefault(speedMps, 15.0),
                orDefault(turnJitterDeg, 15.0),
                classification == null || classification.isBlank() ? "drone" : classification,
                confidence == null ? 0.85f : confidence,
                orDefault(initialAltitudeM, 150.0),
                orDefault(altitudeJitterM, 50.0),
                orDefault(verticalRateMps, 0.0),
                orDefault(minAltitudeM, 100.0),
                orDefault(maxAltitudeM, 500.0));
    }

    private static int    orDefault(Integer v, int d)      { return v == null ? d : v; }
    private static long   orDefault(Long v, long d)        { return v == null ? d : v; }
    private static double orDefault(Double v, double d)    { return v == null ? d : v; }
    private static boolean orDefault(Boolean v, boolean d) { return v == null ? d : v; }
}
