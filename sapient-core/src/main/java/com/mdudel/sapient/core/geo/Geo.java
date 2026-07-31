/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.geo;

import java.util.Random;

/**
 * Small WGS84 helper — good enough for a test bench, not survey-grade.
 *
 * <p>All math uses the "flat-earth-at-current-latitude" approximation
 * which is accurate to within a fraction of a percent for radii up to
 * a few km — perfectly fine for scattering simulated detections around
 * a scenario center.
 */
public final class Geo {

    /** Nautical-ish constant: metres per degree of latitude (WGS84 mean). */
    public static final double METRES_PER_DEG_LAT = 111_320.0;

    private Geo() {
        // utility
    }

    /** Metres per degree of longitude at the given latitude. */
    public static double metresPerDegLon(double latDeg) {
        return METRES_PER_DEG_LAT * Math.cos(Math.toRadians(latDeg));
    }

    /**
     * Offset a WGS84 point by {@code dEastMeters} east and
     * {@code dNorthMeters} north. Returns {@code [lat, lon]}.
     */
    public static double[] offset(double latDeg, double lonDeg,
                                  double dEastMeters, double dNorthMeters) {
        double newLat = latDeg + (dNorthMeters / METRES_PER_DEG_LAT);
        double newLon = lonDeg + (dEastMeters / metresPerDegLon(latDeg));
        return new double[]{newLat, newLon};
    }

    /**
     * Pick a uniformly-distributed random point within a circle of
     * {@code radiusMeters} centered on {@code (latDeg, lonDeg)}.
     */
    public static double[] randomPointInCircle(double latDeg, double lonDeg,
                                               double radiusMeters, Random rnd) {
        // Uniform-in-disc: r = R * sqrt(u), theta = 2*pi*v
        double r = radiusMeters * Math.sqrt(rnd.nextDouble());
        double theta = 2.0 * Math.PI * rnd.nextDouble();
        double dEast = r * Math.cos(theta);
        double dNorth = r * Math.sin(theta);
        return offset(latDeg, lonDeg, dEast, dNorth);
    }

    /** Distance in metres between two WGS84 points (small-area flat approx). */
    public static double distanceMeters(double lat1, double lon1,
                                        double lat2, double lon2) {
        double dLat = (lat2 - lat1) * METRES_PER_DEG_LAT;
        double avgLat = (lat1 + lat2) / 2.0;
        double dLon = (lon2 - lon1) * metresPerDegLon(avgLat);
        return Math.hypot(dLat, dLon);
    }

    /** Compass bearing from p1 to p2 in radians (0 = north, clockwise). */
    public static double bearingRadians(double lat1, double lon1,
                                        double lat2, double lon2) {
        double avgLat = (lat1 + lat2) / 2.0;
        double dEast = (lon2 - lon1) * metresPerDegLon(avgLat);
        double dNorth = (lat2 - lat1) * METRES_PER_DEG_LAT;
        return Math.atan2(dEast, dNorth);
    }
}
