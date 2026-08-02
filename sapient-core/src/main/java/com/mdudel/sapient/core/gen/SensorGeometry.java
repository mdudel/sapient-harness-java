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

    /**
     * Project a sensor coverage envelope as a sector polygon: a fan sweep
     * of {@code azimuthSpanDeg} centred on {@code centreAzimuthDeg}, at
     * {@code rangeMeters} range. Two shapes come out of this helper:
     *
     * <ol>
     *   <li>{@code azimuthSpanDeg >= 360} — a closed ring of {@code
     *       vertexCount} vertices around the sensor (a full disc). No apex
     *       vertex; the polygon is just the arc, closable by the receiver.</li>
     *   <li>Otherwise — a triangular fan: apex at the sensor, followed by
     *       {@code vertexCount - 1} arc vertices evenly spaced across the
     *       sector. Same convention as {@link #projectConeFootprint}.</li>
     * </ol>
     *
     * <p>This is the geometric primitive behind the {@code coverage} slot on
     * {@link SensorGenerator.FovMode#COVERAGE_WITH_OBSCURATION}. It's kept
     * separate from {@link #projectConeFootprint} because coverage semantics
     * differ from FOV semantics: coverage is the sensor's <b>reachable
     * envelope</b> (typically static, often full-disc), while an FOV cone is
     * the live look direction that sweeps every tick.
     *
     * <p>Uses {@link Geo#offset} for the great-circle offset so vertices are
     * geodetically correct at ranges up to a few hundred km. Azimuth is a
     * compass bearing: 0 = north, positive = clockwise.
     *
     * @param sensorLatDeg      apex latitude (decimal degrees)
     * @param sensorLonDeg      apex longitude (decimal degrees)
     * @param centreAzimuthDeg  sector centre bearing; ignored when
     *                          {@code azimuthSpanDeg >= 360}
     * @param azimuthSpanDeg    total sector width; clamped to
     *                          {@code (0, 360]}. Values {@code >= 360}
     *                          trigger the full-disc branch.
     * @param rangeMeters       coverage range (arc radius)
     * @param vertexCount       total vertices in the returned list; clamped
     *                          to {@code >= 3} so the receiver always gets a
     *                          closable polygon
     * @return an ordered list of {@code {lat, lon}} vertex pairs. For sector
     *         mode the apex is first; for full-disc mode there is no apex.
     */
    public static List<double[]> projectSectorFootprint(
            double sensorLatDeg, double sensorLonDeg,
            double centreAzimuthDeg,
            double azimuthSpanDeg,
            double rangeMeters,
            int vertexCount) {

        int total = Math.max(3, vertexCount);
        // Full-disc branch: 360 deg or wider. No apex vertex; the polygon is
        // just the closed ring around the sensor.
        if (azimuthSpanDeg >= 360.0) {
            List<double[]> ring = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                double azDeg = (360.0 * i) / total;
                double azRad = Math.toRadians(azDeg);
                double dEast = rangeMeters * Math.sin(azRad);
                double dNorth = rangeMeters * Math.cos(azRad);
                ring.add(Geo.offset(sensorLatDeg, sensorLonDeg,
                        dEast, dNorth));
            }
            return ring;
        }

        // Sector branch: negative or zero span degenerates to a hairline
        // triangle at the boresight. Clamp so the polygon still closes.
        double effectiveSpan = Math.max(0.0, azimuthSpanDeg);
        // Delegate to projectConeFootprint so cone-vs-sector geometry stays
        // in exactly one place. The two are the same shape when both use
        // apex-first semantics.
        return projectConeFootprint(sensorLatDeg, sensorLonDeg,
                centreAzimuthDeg, effectiveSpan, rangeMeters, total);
    }

    /**
     * Project the polygon that is "disc of radius {@code rangeMeters} MINUS
     * the LOB cone". Used to fill the SAPIENT StatusReport {@code obscuration}
     * slot as the geometric complement of a POLYGON-mode FOV — so a receiver
     * that renders FOV green and obscuration red gets an unambiguous picture:
     * green LOB wedge, red "everything else" around it.
     *
     * <p>Shape: a single closed non-convex "Pac-Man" polygon walking the
     * disc arc from the LOB right edge CCW around to the LOB left edge,
     * then closing via the sensor apex. The polygon has {@code arcVertices
     * + 1} vertices: N points on the far arc + the apex closing point.
     *
     * <p>Edge cases:
     * <ul>
     *   <li>{@code lobSpanDeg >= 360}: LOB covers the whole disc, so
     *       obscuration is empty — returns an empty list.</li>
     *   <li>{@code lobSpanDeg <= 0}: LOB is a hairline; obscuration is
     *       the entire disc as a closed ring (no apex vertex).</li>
     * </ul>
     *
     * @param sensorLatDeg   apex latitude (decimal degrees)
     * @param sensorLonDeg   apex longitude (decimal degrees)
     * @param lobCentreAzDeg LOB boresight bearing (0 = N, +CW)
     * @param lobSpanDeg     LOB horizontal beamwidth (full extent)
     * @param rangeMeters    disc radius — same as the LOB range so the FOV
     *                       and its obscuration complement together tile
     *                       the whole disc with no gap.
     * @param arcVertices    vertices along the far arc; clamped to
     *                       {@code >= 3}. More = smoother arc, at wire cost.
     * @return one polygon in {@code {lat, lon}} pairs, or empty when the
     *         LOB covers the full disc. Wrapped in a list for symmetry with
     *         SAPIENT's {@code repeated obscuration} slot.
     */
    public static List<List<double[]>> projectAntiLobPolygon(
            double sensorLatDeg, double sensorLonDeg,
            double lobCentreAzDeg,
            double lobSpanDeg,
            double rangeMeters,
            int arcVertices) {

        if (lobSpanDeg >= 360.0) return new ArrayList<>();

        int n = Math.max(3, arcVertices);

        // Hairline LOB: obscuration = full disc (closed ring, no apex).
        if (lobSpanDeg <= 0.0) {
            List<double[]> ring = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double azDeg = (360.0 * i) / n;
                double azRad = Math.toRadians(azDeg);
                ring.add(Geo.offset(sensorLatDeg, sensorLonDeg,
                        rangeMeters * Math.sin(azRad),
                        rangeMeters * Math.cos(azRad)));
            }
            List<List<double[]>> out = new ArrayList<>(1);
            out.add(ring);
            return out;
        }

        // Pac-Man: apex-first, then arc from LOB right edge CCW around to
        // LOB left edge, evenly spaced across (360 - lobSpan) degrees.
        double halfLob = lobSpanDeg / 2.0;
        double antiSpan = 360.0 - lobSpanDeg;
        double startAz = lobCentreAzDeg + halfLob;
        List<double[]> poly = new ArrayList<>(n + 1);
        poly.add(new double[] { sensorLatDeg, sensorLonDeg });   // apex
        for (int i = 0; i < n; i++) {
            double t = (n == 1) ? 0.0 : (double) i / (n - 1);
            double azDeg = startAz + t * antiSpan;
            double azRad = Math.toRadians(azDeg);
            poly.add(Geo.offset(sensorLatDeg, sensorLonDeg,
                    rangeMeters * Math.sin(azRad),
                    rangeMeters * Math.cos(azRad)));
        }
        List<List<double[]>> out = new ArrayList<>(1);
        out.add(poly);
        return out;
    }
}
