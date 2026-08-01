/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.gen;

import com.mdudel.sapient.core.geo.Geo;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared geometry helpers for building SAPIENT field-of-view payloads.
 *
 * <p>Extracted 2026-08-01 so both the live {@link SensorGenerator} ticker
 * and the one-shot {@code MessageDialogs.statusReport} dialog can call the
 * same cone-to-polygon projection code. Keeping this in a static helper
 * means there's exactly one place that decides how a cone maps to a ground
 * footprint — no risk of the live and one-shot paths drifting apart.
 */
public final class SensorGeometry {

    private SensorGeometry() {
        // utility
    }

    /**
     * Project a sensor cone's ground footprint as a triangular fan polygon.
     * The polygon has {@code vertexCount} vertices in total:
     * <ol>
     *   <li>The first vertex is the apex at
     *       {@code (sensorLatDeg, sensorLonDeg)}.</li>
     *   <li>The remaining {@code vertexCount - 1} vertices trace an arc at
     *       {@code rangeMeters} out from the apex, evenly spaced across
     *       {@code [boresightAzimuthDeg - horizontalExtentDeg/2,
     *              boresightAzimuthDeg + horizontalExtentDeg/2]}.</li>
     * </ol>
     *
     * <p>Uses {@link Geo#offset} for the great-circle offset so vertices are
     * geodetically correct at ranges up to a few hundred km. Azimuth is a
     * compass bearing: 0 = north, positive = clockwise.
     *
     * @param sensorLatDeg          apex latitude (decimal degrees)
     * @param sensorLonDeg          apex longitude (decimal degrees)
     * @param boresightAzimuthDeg   direction the cone is pointing
     * @param horizontalExtentDeg   total horizontal beamwidth
     * @param rangeMeters           cone range (arc radius)
     * @param vertexCount           total vertices in the returned list;
     *                              clamped to {@code >= 3} so the receiver
     *                              always gets a closable polygon
     * @return an ordered list of {@code {lat, lon}} vertex pairs, apex first
     */
    public static List<double[]> projectConeFootprint(
            double sensorLatDeg, double sensorLonDeg,
            double boresightAzimuthDeg,
            double horizontalExtentDeg,
            double rangeMeters,
            int vertexCount) {

        int total = Math.max(3, vertexCount);
        List<double[]> vertices = new ArrayList<>(total);
        // Apex first
        vertices.add(new double[] { sensorLatDeg, sensorLonDeg });

        double halfExtent = horizontalExtentDeg / 2.0;
        int arcVerts = Math.max(2, total - 1);
        for (int i = 0; i < arcVerts; i++) {
            double t = (arcVerts == 1) ? 0.0 : (double) i / (arcVerts - 1);
            double azDeg = boresightAzimuthDeg - halfExtent
                    + t * horizontalExtentDeg;
            double azRad = Math.toRadians(azDeg);
            double dEast = rangeMeters * Math.sin(azRad);
            double dNorth = rangeMeters * Math.cos(azRad);
            double[] p = Geo.offset(sensorLatDeg, sensorLonDeg, dEast, dNorth);
            vertices.add(p);
        }
        return vertices;
    }
}
