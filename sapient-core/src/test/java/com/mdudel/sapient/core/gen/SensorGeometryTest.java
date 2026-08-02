/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.gen;

import com.mdudel.sapient.core.geo.Geo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link SensorGeometry} — pure math, no protobuf, no
 * JavaFX, no sockets. Runs headless in the sandbox.
 */
class SensorGeometryTest {

    // Wiesbaden, per Marty's scenario default.
    private static final double LAT = 50.0782;
    private static final double LON = 8.2398;

    // ---- projectConeFootprint (baseline regression) ----------------------

    @Test
    void coneFootprintFirstVertexIsApex() {
        List<double[]> verts = SensorGeometry.projectConeFootprint(
                LAT, LON, /*az*/ 90.0, /*extent*/ 30.0, /*range*/ 1000.0, 5);
        assertThat(verts).hasSize(5);
        assertThat(verts.get(0)[0]).isCloseTo(LAT, within(1e-12));
        assertThat(verts.get(0)[1]).isCloseTo(LON, within(1e-12));
    }

    @Test
    void coneFootprintArcVerticesAtRequestedRange() {
        double rangeM = 2500.0;
        List<double[]> verts = SensorGeometry.projectConeFootprint(
                LAT, LON, 0.0, 60.0, rangeM, 7);
        // Skip the apex; every arc vertex should be ~rangeM from the apex.
        for (int i = 1; i < verts.size(); i++) {
            double d = Geo.distanceMeters(LAT, LON,
                    verts.get(i)[0], verts.get(i)[1]);
            // Great-circle vs flat-earth projection drift on 2.5 km: <0.5 m
            assertThat(d).isCloseTo(rangeM, within(1.0));
        }
    }

    // ---- projectSectorFootprint (new) ------------------------------------

    @Test
    void sectorFootprintSectorModeHasApexFirst() {
        List<double[]> verts = SensorGeometry.projectSectorFootprint(
                LAT, LON, /*centre*/ 45.0, /*span*/ 90.0,
                /*range*/ 1500.0, /*n*/ 9);
        assertThat(verts).hasSize(9);
        assertThat(verts.get(0)[0]).isCloseTo(LAT, within(1e-12));
        assertThat(verts.get(0)[1]).isCloseTo(LON, within(1e-12));
    }

    @Test
    void sectorFootprintSectorArcAtRequestedRange() {
        double rangeM = 3000.0;
        List<double[]> verts = SensorGeometry.projectSectorFootprint(
                LAT, LON, 180.0, 120.0, rangeM, 12);
        for (int i = 1; i < verts.size(); i++) {
            double d = Geo.distanceMeters(LAT, LON,
                    verts.get(i)[0], verts.get(i)[1]);
            assertThat(d).isCloseTo(rangeM, within(1.5));
        }
    }

    @Test
    void sectorFootprintSectorSweepMatchesCentreAndHalfSpan() {
        // 90-deg sector centred on 0 (north) spans azimuth [-45, +45].
        // With vertexCount=3 that's apex + 2 arc vertices at those endpoints.
        List<double[]> verts = SensorGeometry.projectSectorFootprint(
                LAT, LON, 0.0, 90.0, 1000.0, 3);
        assertThat(verts).hasSize(3);
        // Arc vertex 1 at az = -45 (NW quadrant): dEast<0, dNorth>0
        double[] leftEdge = verts.get(1);
        assertThat(leftEdge[1]).isLessThan(LON);   // west of apex
        assertThat(leftEdge[0]).isGreaterThan(LAT); // north of apex
        // Arc vertex 2 at az = +45 (NE quadrant): dEast>0, dNorth>0
        double[] rightEdge = verts.get(2);
        assertThat(rightEdge[1]).isGreaterThan(LON); // east of apex
        assertThat(rightEdge[0]).isGreaterThan(LAT); // north of apex
    }

    @Test
    void sectorFootprintFullDiscHasNoApexAndClosesTheRing() {
        int n = 36;
        double rangeM = 5000.0;
        List<double[]> ring = SensorGeometry.projectSectorFootprint(
                LAT, LON, /*centre*/ 123.0 /*ignored*/, /*span*/ 360.0,
                rangeM, n);
        assertThat(ring).hasSize(n);
        // No vertex should be AT the apex — this is a ring, not a fan.
        for (double[] v : ring) {
            double d = Geo.distanceMeters(LAT, LON, v[0], v[1]);
            assertThat(d).isCloseTo(rangeM, within(1.5));
        }
        // First vertex at azimuth 0 (due north) → dEast≈0, dNorth>0
        assertThat(ring.get(0)[1]).isCloseTo(LON, within(1e-6));
        assertThat(ring.get(0)[0]).isGreaterThan(LAT);
    }

    @Test
    void sectorFootprintSpanGreaterThan360IsStillFullDisc() {
        List<double[]> ring = SensorGeometry.projectSectorFootprint(
                LAT, LON, 0.0, 720.0, 500.0, 8);
        // Same 8 evenly-spaced ring vertices, not a double-wrapped
        // 16-vertex fan.
        assertThat(ring).hasSize(8);
        for (double[] v : ring) {
            assertThat(Geo.distanceMeters(LAT, LON, v[0], v[1]))
                    .isCloseTo(500.0, within(0.5));
        }
    }

    @Test
    void sectorFootprintClampsBelowThreeVertices() {
        List<double[]> verts = SensorGeometry.projectSectorFootprint(
                LAT, LON, 0.0, 90.0, 1000.0, /*n*/ 1);
        assertThat(verts).hasSize(3);
    }

    @Test
    void sectorFootprintNegativeSpanClampsToZeroDegenerate() {
        // A negative span should NOT explode; it collapses to a hairline
        // sector at the boresight. Still 3 vertices (apex + 2 co-located
        // arc endpoints).
        List<double[]> verts = SensorGeometry.projectSectorFootprint(
                LAT, LON, 90.0, -30.0, 1000.0, 3);
        assertThat(verts).hasSize(3);
        // Arc vertices should be co-located at the boresight (az=90 → east).
        double[] a = verts.get(1);
        double[] b = verts.get(2);
        assertThat(a[0]).isCloseTo(b[0], within(1e-9));
        assertThat(a[1]).isCloseTo(b[1], within(1e-9));
        // And they should be due east of the apex.
        assertThat(a[0]).isCloseTo(LAT, within(1e-6));
        assertThat(a[1]).isGreaterThan(LON);
    }

    // ---- projectAntiLobPolygon (POLYGON-mode obscuration) -------------

    @Test
    void antiLobEmptyWhenLobCovers360() {
        List<List<double[]>> out = SensorGeometry.projectAntiLobPolygon(
                LAT, LON, /*az*/ 0.0, /*span*/ 360.0,
                /*range*/ 5000.0, /*arcVerts*/ 24);
        assertThat(out).isEmpty();
    }

    @Test
    void antiLobEmptyWhenLobOverflows360() {
        List<List<double[]>> out = SensorGeometry.projectAntiLobPolygon(
                LAT, LON, 45.0, 400.0, 5000.0, 24);
        assertThat(out).isEmpty();
    }

    @Test
    void antiLobHairlineLobProducesFullDiscRingWithNoApex() {
        int n = 24;
        List<List<double[]>> out = SensorGeometry.projectAntiLobPolygon(
                LAT, LON, 90.0, /*lobSpan*/ 0.0, /*range*/ 3000.0, n);
        assertThat(out).hasSize(1);
        List<double[]> ring = out.get(0);
        // Hairline LOB -> obscuration is a bare closed ring, N arc verts,
        // no apex.
        assertThat(ring).hasSize(n);
        for (double[] v : ring) {
            double d = Geo.distanceMeters(LAT, LON, v[0], v[1]);
            assertThat(d).isCloseTo(3000.0, within(1.0));
        }
    }

    @Test
    void antiLobRegularLobIsPacManApexPlusArc() {
        double lobSpan = 30.0;
        int arcN = 24;
        List<List<double[]>> out = SensorGeometry.projectAntiLobPolygon(
                LAT, LON, /*centre*/ 90.0, lobSpan,
                /*range*/ 4000.0, arcN);
        assertThat(out).hasSize(1);
        List<double[]> pac = out.get(0);
        // Apex first, then arcN arc vertices.
        assertThat(pac).hasSize(arcN + 1);
        assertThat(pac.get(0)[0]).isCloseTo(LAT, within(1e-12));
        assertThat(pac.get(0)[1]).isCloseTo(LON, within(1e-12));
        // Every arc vertex should be ~range from the apex.
        for (int i = 1; i < pac.size(); i++) {
            double d = Geo.distanceMeters(LAT, LON, pac.get(i)[0], pac.get(i)[1]);
            assertThat(d).isCloseTo(4000.0, within(2.0));
        }
    }

    @Test
    void antiLobArcStartsAtLobRightEdgeAndEndsAtLobLeftEdge() {
        // LOB centred on 0 (north), 60 deg span -> right edge at az=+30,
        // left edge at az=-30 (which is 330). The Pac-Man arc walks CCW
        // from +30 around through 180 back to 330 (span 300 deg).
        // Use arcN=3 so vertex indices are: 1=first(+30), 2=mid(+180),
        // 3=last(+330). arcN is also the min allowed after the clamp.
        List<List<double[]>> out = SensorGeometry.projectAntiLobPolygon(
                LAT, LON, /*centre*/ 0.0, /*span*/ 60.0,
                /*range*/ 2000.0, /*arcN*/ 3);
        List<double[]> pac = out.get(0);
        assertThat(pac).hasSize(4);   // apex + 3 arc verts
        // First arc vertex: az=+30 -> NE quadrant (lon>LON, lat>LAT).
        assertThat(pac.get(1)[0]).isGreaterThan(LAT);
        assertThat(pac.get(1)[1]).isGreaterThan(LON);
        // Middle arc vertex: az=+180 -> due south (lat<LAT, lon~LON).
        assertThat(pac.get(2)[0]).isLessThan(LAT);
        assertThat(pac.get(2)[1]).isCloseTo(LON, within(1e-6));
        // Last arc vertex: az=+330 -> NW quadrant (lon<LON, lat>LAT).
        assertThat(pac.get(3)[0]).isGreaterThan(LAT);
        assertThat(pac.get(3)[1]).isLessThan(LON);
    }

    @Test
    void antiLobArcVertsClampToThree() {
        List<List<double[]>> out = SensorGeometry.projectAntiLobPolygon(
                LAT, LON, 90.0, 30.0, 3000.0, /*arcN*/ 1);
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).hasSize(1 + 3); // apex + clamped arc
    }
}
