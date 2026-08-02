/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.factory;

import org.junit.jupiter.api.Test;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationList;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationOrRangeBearing;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RangeBearingCone;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RangeBearingDatum;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the 13-arg {@code MessageFactory.statusReport} overload that
 * populates the {@code coverage[]} and {@code obscuration[]} repeated
 * slots alongside {@code fieldOfView}. Also verifies that the existing
 * 11-arg overload delegates cleanly (empty coverage/obscuration on the
 * wire, byte-compatible with pre-existing consumers).
 */
class MessageFactoryStatusReportCoverageTest {

    private static final double LAT = 50.0782;
    private static final double LON = 8.2398;
    private static final Double ALT = 100.0;
    private static final String NODE = "test-node-uuid";

    // ---- helpers ---------------------------------------------------------

    private static LocationOrRangeBearing samplePolygon(double dOffset) {
        // Little square around a point; distinct per test so we can prove
        // the right one landed in the right slot.
        LocationList poly = MessageFactory.polygon(List.of(
                new double[] { LAT + dOffset,       LON },
                new double[] { LAT + dOffset,       LON + 0.001 },
                new double[] { LAT + dOffset + 0.001, LON + 0.001 },
                new double[] { LAT + dOffset + 0.001, LON }
        ), ALT);
        return MessageFactory.fovPolygon(poly);
    }

    private static LocationOrRangeBearing sampleCone() {
        RangeBearingCone cone = MessageFactory.cone(
                90.0, 5.0, 5000.0, 30.0, 5.0,
                RangeBearingDatum.RANGE_BEARING_DATUM_TRUE);
        return MessageFactory.fovCone(cone);
    }

    // ---- back-compat: old 11-arg overload keeps empty repeated slots ----

    @Test
    void elevenArgOverloadEmitsNoCoverageOrObscuration() {
        SapientMessage msg = MessageFactory.statusReport(NODE,
                StatusReport.System.SYSTEM_OK, StatusReport.Info.INFO_NEW,
                "scanning", LAT, LON, ALT,
                null, null, null,
                sampleCone());

        StatusReport sr = msg.getStatusReport();
        assertThat(sr.getFieldOfView().hasRangeBearing()).isTrue();
        assertThat(sr.getCoverageCount()).isZero();
        assertThat(sr.getObscurationCount()).isZero();
    }

    // ---- 13-arg overload: happy path ------------------------------------

    @Test
    void thirteenArgOverloadPopulatesAllThreeSlots() {
        LocationOrRangeBearing fov      = samplePolygon(0.000);
        LocationOrRangeBearing coverage = samplePolygon(0.010);
        LocationOrRangeBearing obs1     = samplePolygon(0.020);
        LocationOrRangeBearing obs2     = samplePolygon(0.030);

        SapientMessage msg = MessageFactory.statusReport(NODE,
                StatusReport.System.SYSTEM_OK, StatusReport.Info.INFO_UNCHANGED,
                "scanning", LAT, LON, ALT,
                null, null, null,
                fov,
                List.of(coverage),
                List.of(obs1, obs2));

        StatusReport sr = msg.getStatusReport();
        assertThat(sr.hasFieldOfView()).isTrue();
        assertThat(sr.getFieldOfView().hasLocationList()).isTrue();

        assertThat(sr.getCoverageCount()).isEqualTo(1);
        assertThat(sr.getCoverage(0).hasLocationList()).isTrue();

        assertThat(sr.getObscurationCount()).isEqualTo(2);
        assertThat(sr.getObscuration(0).hasLocationList()).isTrue();
        assertThat(sr.getObscuration(1).hasLocationList()).isTrue();
    }

    // ---- guards ---------------------------------------------------------

    @Test
    void nullListsAreTreatedAsEmpty() {
        SapientMessage msg = MessageFactory.statusReport(NODE,
                StatusReport.System.SYSTEM_OK, StatusReport.Info.INFO_NEW,
                "scanning", LAT, LON, ALT,
                null, null, null,
                sampleCone(),
                null,
                null);

        StatusReport sr = msg.getStatusReport();
        assertThat(sr.getCoverageCount()).isZero();
        assertThat(sr.getObscurationCount()).isZero();
    }

    @Test
    void emptyListsLeaveSlotsAbsent() {
        SapientMessage msg = MessageFactory.statusReport(NODE,
                StatusReport.System.SYSTEM_OK, StatusReport.Info.INFO_NEW,
                "scanning", LAT, LON, ALT,
                null, null, null,
                sampleCone(),
                Collections.emptyList(),
                Collections.emptyList());

        StatusReport sr = msg.getStatusReport();
        assertThat(sr.getCoverageCount()).isZero();
        assertThat(sr.getObscurationCount()).isZero();
    }

    @Test
    void nullElementsInsideListAreSkippedDefensively() {
        LocationOrRangeBearing good = samplePolygon(0.005);

        SapientMessage msg = MessageFactory.statusReport(NODE,
                StatusReport.System.SYSTEM_OK, StatusReport.Info.INFO_NEW,
                "scanning", LAT, LON, ALT,
                null, null, null,
                sampleCone(),
                java.util.Arrays.asList(good, null, good),
                java.util.Arrays.asList((LocationOrRangeBearing) null));

        StatusReport sr = msg.getStatusReport();
        assertThat(sr.getCoverageCount()).isEqualTo(2);   // null skipped
        assertThat(sr.getObscurationCount()).isZero();    // sole null skipped
    }

    @Test
    void wireRoundTripPreservesCoverageAndObscuration() throws Exception {
        SapientMessage sent = MessageFactory.statusReport(NODE,
                StatusReport.System.SYSTEM_OK, StatusReport.Info.INFO_NEW,
                "scanning", LAT, LON, ALT,
                null, null, null,
                samplePolygon(0.0),
                List.of(samplePolygon(0.01)),
                List.of(samplePolygon(0.02), samplePolygon(0.03)));

        byte[] bytes = sent.toByteArray();
        SapientMessage got = SapientMessage.parseFrom(bytes);

        StatusReport sr = got.getStatusReport();
        assertThat(sr.getCoverageCount()).isEqualTo(1);
        assertThat(sr.getObscurationCount()).isEqualTo(2);
        assertThat(sr.getFieldOfView().hasLocationList()).isTrue();
    }
}
