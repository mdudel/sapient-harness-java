/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.gen;

import org.junit.jupiter.api.Test;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the three {@link SensorGenerator.FovMode} values ship the
 * expected wire shapes:
 * <ul>
 *   <li>CONE \u2192 RangeBearingCone in {@code field_of_view}, no obscuration.</li>
 *   <li>POLYGON \u2192 LocationList polygon in {@code field_of_view}, no obscuration.</li>
 *   <li>POLYGON_WITH_OBSCURATION \u2192 LocationList polygon in
 *       {@code field_of_view} PLUS the disc-minus-LOB polygon in
 *       {@code obscuration[0]}.</li>
 * </ul>
 * Coverage is always empty on the wire for all three modes (SensorGenerator
 * does not author coverage envelopes).
 */
class SensorGeneratorPolygonObscurationTest {

    private static final double LAT = 50.0782;
    private static final double LON = 8.2398;

    private static SensorGenerator.Config configFor(SensorGenerator.FovMode mode) {
        return new SensorGenerator.Config(
                "test-node", /*tickMs*/ 50,
                LAT, LON, /*altM*/ 100.0,
                /*moving*/ false, 0.0, 0.0, 0.0,
                mode,
                /*az0*/ 90.0, /*azRate*/ 0.0,
                /*el0*/ 0.0, 0.0, 10.0, 0.0,
                /*range*/ 5000.0,
                /*hExtent*/ 30.0, /*vExtent*/ 5.0,
                /*polyVerts*/ 8,
                StatusReport.System.SYSTEM_OK, "scanning",
                null, null);
    }

    private static SapientMessage captureOneTick(SensorGenerator.Config cfg)
            throws InterruptedException {
        ConcurrentLinkedQueue<SapientMessage> captured = new ConcurrentLinkedQueue<>();
        try (SensorGenerator gen = new SensorGenerator(cfg, captured::add, 1234L)) {
            gen.start();
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(500);
            while (captured.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            gen.stop();
        }
        SapientMessage m = captured.peek();
        assertThat(m).as("no message within timeout").isNotNull();
        return m;
    }

    @Test
    void coneModeEmitsRangeBearingConeOnly() throws Exception {
        SapientMessage msg = captureOneTick(configFor(SensorGenerator.FovMode.CONE));
        StatusReport sr = msg.getStatusReport();

        assertThat(sr.hasFieldOfView()).isTrue();
        assertThat(sr.getFieldOfView().hasRangeBearing()).isTrue();
        assertThat(sr.getCoverageCount()).isZero();
        assertThat(sr.getObscurationCount()).isZero();
    }

    @Test
    void polygonModeEmitsFovOnlyNoObscuration() throws Exception {
        SapientMessage msg = captureOneTick(configFor(SensorGenerator.FovMode.POLYGON));
        StatusReport sr = msg.getStatusReport();

        // FOV: the LOB polygon.
        assertThat(sr.hasFieldOfView()).isTrue();
        assertThat(sr.getFieldOfView().hasLocationList()).isTrue();

        // Coverage: unused.
        assertThat(sr.getCoverageCount()).isZero();

        // Obscuration: MUST be empty in plain POLYGON mode. This is the
        // 2026-08-03 correction \u2014 previously POLYGON auto-emitted the
        // anti-LOB Pac-Man polygon; that behaviour moved to
        // POLYGON_WITH_OBSCURATION.
        assertThat(sr.getObscurationCount()).isZero();
    }

    @Test
    void polygonWithObscurationEmitsFovAndAntiLob() throws Exception {
        SapientMessage msg = captureOneTick(
                configFor(SensorGenerator.FovMode.POLYGON_WITH_OBSCURATION));
        StatusReport sr = msg.getStatusReport();

        // FOV: the LOB polygon (identical geometry to plain POLYGON mode).
        assertThat(sr.hasFieldOfView()).isTrue();
        assertThat(sr.getFieldOfView().hasLocationList()).isTrue();

        // Coverage: still unused in this mode too.
        assertThat(sr.getCoverageCount()).isZero();

        // Obscuration: exactly one polygon (the Pac-Man complement).
        assertThat(sr.getObscurationCount()).isEqualTo(1);
        assertThat(sr.getObscuration(0).hasLocationList()).isTrue();
    }
}
