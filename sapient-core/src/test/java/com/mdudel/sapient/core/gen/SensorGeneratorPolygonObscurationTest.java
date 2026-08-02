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
 * Verifies POLYGON-mode SensorGenerator emits BOTH the FOV polygon AND
 * the geometric complement (disc-minus-LOB) as {@code obscuration[0]}.
 * CONE mode remains obscuration-free.
 */
class SensorGeneratorPolygonObscurationTest {

    private static final double LAT = 50.0782;
    private static final double LON = 8.2398;

    private static SensorGenerator.Config polygonConfig() {
        return new SensorGenerator.Config(
                "test-node", /*tickMs*/ 50,
                LAT, LON, /*altM*/ 100.0,
                /*moving*/ false, 0.0, 0.0, 0.0,
                SensorGenerator.FovMode.POLYGON,
                /*az0*/ 90.0, /*azRate*/ 0.0,
                /*el0*/ 0.0, 0.0, 0.0, 0.0,
                /*range*/ 5000.0,
                /*hExtent*/ 30.0, /*vExtent*/ 5.0,
                /*polyVerts*/ 8,
                StatusReport.System.SYSTEM_OK, "scanning",
                null, null);
    }

    private static SensorGenerator.Config coneConfig() {
        return new SensorGenerator.Config(
                "test-cone", 50,
                LAT, LON, 100.0,
                false, 0.0, 0.0, 0.0,
                SensorGenerator.FovMode.CONE,
                90.0, 0.0,
                5.0, 0.0, 10.0, 0.0,
                5000.0,
                30.0, 5.0,
                8,
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
    void polygonModeEmitsFovAndAntiLobObscuration() throws Exception {
        SapientMessage msg = captureOneTick(polygonConfig());
        StatusReport sr = msg.getStatusReport();

        // FOV: the LOB polygon.
        assertThat(sr.hasFieldOfView()).isTrue();
        assertThat(sr.getFieldOfView().hasLocationList()).isTrue();

        // Coverage: still unused in POLYGON mode.
        assertThat(sr.getCoverageCount()).isZero();

        // Obscuration: exactly one polygon (the Pac-Man complement).
        assertThat(sr.getObscurationCount()).isEqualTo(1);
        assertThat(sr.getObscuration(0).hasLocationList()).isTrue();
    }

    @Test
    void coneModeIsUnchangedByPolygonAutoObscuration() throws Exception {
        SapientMessage msg = captureOneTick(coneConfig());
        StatusReport sr = msg.getStatusReport();

        assertThat(sr.hasFieldOfView()).isTrue();
        assertThat(sr.getFieldOfView().hasRangeBearing()).isTrue();
        assertThat(sr.getCoverageCount()).isZero();
        assertThat(sr.getObscurationCount()).isZero();
    }
}
