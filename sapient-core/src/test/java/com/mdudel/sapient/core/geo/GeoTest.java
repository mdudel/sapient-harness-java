/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.geo;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoTest {

    // Wiesbaden, per Marty's request as scenario default.
    private static final double WIESBADEN_LAT = 50.0782;
    private static final double WIESBADEN_LON = 8.2398;

    @Test
    void offsetOneKmNorthMovesTheLatByAboutRightAmount() {
        double[] out = Geo.offset(WIESBADEN_LAT, WIESBADEN_LON, 0, 1000);
        // 1 km north ≈ 0.00898 degrees of latitude
        assertThat(out[0]).isCloseTo(WIESBADEN_LAT + 0.00898, within(0.0002));
        assertThat(out[1]).isCloseTo(WIESBADEN_LON, within(1e-9));
    }

    @Test
    void randomPointInCircleStaysInCircle() {
        Random rnd = new Random(42);
        double radius = 500.0;
        for (int i = 0; i < 1000; i++) {
            double[] p = Geo.randomPointInCircle(WIESBADEN_LAT, WIESBADEN_LON,
                    radius, rnd);
            double d = Geo.distanceMeters(WIESBADEN_LAT, WIESBADEN_LON, p[0], p[1]);
            assertThat(d).isLessThanOrEqualTo(radius + 0.5); // tiny numerical slack
        }
    }

    @Test
    void distanceRoundTripIsAccurate() {
        double[] p = Geo.offset(WIESBADEN_LAT, WIESBADEN_LON, 300, 400); // 500 m at 3-4-5
        double d = Geo.distanceMeters(WIESBADEN_LAT, WIESBADEN_LON, p[0], p[1]);
        assertThat(d).isCloseTo(500.0, within(0.5));
    }
}
