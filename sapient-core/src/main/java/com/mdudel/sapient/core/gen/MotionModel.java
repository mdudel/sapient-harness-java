/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.gen;

import com.mdudel.sapient.core.geo.Geo;

import java.util.Random;

/**
 * Shared 2D linear-motion model with heading jitter + centre-corralling.
 *
 * <p>Extracted 2026-08-01 from {@link DetectionGenerator} so the incoming
 * {@code SensorGenerator} (moving-platform mode) can share the exact same
 * motion physics — same jitter behaviour, same 3x-radius corral rule,
 * same great-circle offset math. Detection tracks and sensor platforms
 * now walk the ground the same way, which makes multi-source scenarios
 * deterministic against the same random seed.
 *
 * <p><b>Behaviour</b> per {@link #step}:
 * <ol>
 *   <li>Heading is nudged by up to ±{@code turnJitterDeg}° per call.</li>
 *   <li>If the point is beyond {@code 3 × radiusMeters} from centre, the
 *       heading is blended 30% toward the bearing-to-centre so runaway
 *       tracks curve back rather than wandering off forever.</li>
 *   <li>Position advances {@code speedMps × dtSeconds} metres along the
 *       (jittered) heading using great-circle offset.</li>
 * </ol>
 *
 * <p><b>Compass convention:</b> {@code headingRad} is a compass bearing —
 * 0 = north, π/2 = east, increases clockwise. Matches the offset helper
 * in {@link Geo} and the pre-refactor {@code DetectionGenerator} exactly.
 *
 * <p><b>Threading:</b> mutable state lives on a {@link State} instance —
 * one per moving thing. The model class itself is stateless and thread-safe;
 * a caller only needs to serialise access to a given {@code State}.
 */
public final class MotionModel {

    /** Immutable configuration for one moving thing. */
    public static final class Params {
        public final double centerLat;
        public final double centerLon;
        public final double radiusMeters;
        public final double speedMps;
        public final double turnJitterDeg;

        public Params(double centerLat, double centerLon,
                      double radiusMeters,
                      double speedMps, double turnJitterDeg) {
            this.centerLat = centerLat;
            this.centerLon = centerLon;
            this.radiusMeters = radiusMeters;
            this.speedMps = speedMps;
            this.turnJitterDeg = turnJitterDeg;
        }
    }

    /** Mutable position + heading for one moving thing. */
    public static final class State {
        public double lat;
        public double lon;
        /** Compass bearing in radians: 0 = north, CW. */
        public double headingRad;

        public State(double lat, double lon, double headingRad) {
            this.lat = lat;
            this.lon = lon;
            this.headingRad = headingRad;
        }
    }

    private MotionModel() {
        // utility
    }

    /**
     * Advance {@code state} in place by {@code dtSeconds} of motion under
     * {@code params}, using {@code rnd} for heading jitter. Call once per
     * tick per moving thing.
     */
    public static void step(State state, Params params, double dtSeconds, Random rnd) {
        // 1) Heading jitter
        double jitterRad = Math.toRadians(
                (rnd.nextDouble() * 2.0 - 1.0) * params.turnJitterDeg);
        state.headingRad += jitterRad;

        // 2) Corral runaway motion back toward the centre when > 3 × radius
        double distFromCenter = Geo.distanceMeters(
                params.centerLat, params.centerLon, state.lat, state.lon);
        if (distFromCenter > params.radiusMeters * 3) {
            double bearingToCenter = Geo.bearingRadians(
                    state.lat, state.lon, params.centerLat, params.centerLon);
            state.headingRad = angleLerp(state.headingRad, bearingToCenter, 0.30);
        }

        // 3) Advance by great-circle offset
        double meters = params.speedMps * dtSeconds;
        double dEast = meters * Math.sin(state.headingRad);
        double dNorth = meters * Math.cos(state.headingRad);
        double[] newP = Geo.offset(state.lat, state.lon, dEast, dNorth);
        state.lat = newP[0];
        state.lon = newP[1];
    }

    /** Interpolate between two angles the short way around. */
    static double angleLerp(double a, double b, double t) {
        double diff = Math.atan2(Math.sin(b - a), Math.cos(b - a));
        return a + diff * t;
    }
}
