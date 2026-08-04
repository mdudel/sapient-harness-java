/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.protocol;

import org.junit.jupiter.api.Test;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusIntervalsTest {

    // ------------------------------------------------------------------
    // Proto → Java
    // ------------------------------------------------------------------

    @Test
    void secondsConvertToDuration() {
        Registration.Duration d = Registration.Duration.newBuilder()
                .setUnits(Registration.TimeUnits.TIME_UNITS_SECONDS)
                .setValue(5f)
                .build();
        assertThat(StatusIntervals.toJavaDuration(d)).isEqualTo(Duration.ofSeconds(5));
        assertThat(StatusIntervals.toMillis(d)).isEqualTo(5_000L);
    }

    @Test
    void millisecondsConvertToDuration() {
        Registration.Duration d = Registration.Duration.newBuilder()
                .setUnits(Registration.TimeUnits.TIME_UNITS_MILLISECONDS)
                .setValue(250f)
                .build();
        assertThat(StatusIntervals.toMillis(d)).isEqualTo(250L);
    }

    @Test
    void allUnitsRoundtripThroughMillis() {
        // 5 minutes → 300 000 ms
        assertThat(StatusIntervals.toMillis(build(Registration.TimeUnits.TIME_UNITS_MINUTES, 5f)))
                .isEqualTo(300_000L);
        // 2 hours → 7 200 000 ms
        assertThat(StatusIntervals.toMillis(build(Registration.TimeUnits.TIME_UNITS_HOURS, 2f)))
                .isEqualTo(7_200_000L);
        // 1 day → 86 400 000 ms
        assertThat(StatusIntervals.toMillis(build(Registration.TimeUnits.TIME_UNITS_DAYS, 1f)))
                .isEqualTo(86_400_000L);
        // 500 000 microseconds → 500 ms
        assertThat(StatusIntervals.toMillis(build(Registration.TimeUnits.TIME_UNITS_MICROSECONDS, 500_000f)))
                .isEqualTo(500L);
        // 1_500_000_000 ns → 1500 ms
        assertThat(StatusIntervals.toMillis(build(Registration.TimeUnits.TIME_UNITS_NANOSECONDS, 1_500_000_000f)))
                .isEqualTo(1_500L);
    }

    @Test
    void unspecifiedUnitsRejected() {
        Registration.Duration d = Registration.Duration.newBuilder()
                .setValue(5f)
                .build(); // units defaults to UNSPECIFIED
        assertThatThrownBy(() -> StatusIntervals.toJavaDuration(d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("units must be set");
    }

    @Test
    void nullDurationRejected() {
        assertThatThrownBy(() -> StatusIntervals.toJavaDuration(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeValueRejected() {
        Registration.Duration d = build(Registration.TimeUnits.TIME_UNITS_SECONDS, -1f);
        assertThatThrownBy(() -> StatusIntervals.toJavaDuration(d))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nanValueRejected() {
        Registration.Duration d = build(Registration.TimeUnits.TIME_UNITS_SECONDS, Float.NaN);
        assertThatThrownBy(() -> StatusIntervals.toJavaDuration(d))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Java → Proto (lossless-preferred coarsening)
    // ------------------------------------------------------------------

    @Test
    void fiveSecondsSerialisesAsSeconds() {
        Registration.Duration d = StatusIntervals.fromJavaDuration(Duration.ofSeconds(5));
        assertThat(d.getUnits()).isEqualTo(Registration.TimeUnits.TIME_UNITS_SECONDS);
        assertThat(d.getValue()).isEqualTo(5f);
    }

    @Test
    void oneMinuteSerialisesAsMinutes() {
        Registration.Duration d = StatusIntervals.fromJavaDuration(Duration.ofMinutes(1));
        assertThat(d.getUnits()).isEqualTo(Registration.TimeUnits.TIME_UNITS_MINUTES);
        assertThat(d.getValue()).isEqualTo(1f);
    }

    @Test
    void oneHourSerialisesAsHours() {
        Registration.Duration d = StatusIntervals.fromJavaDuration(Duration.ofHours(1));
        assertThat(d.getUnits()).isEqualTo(Registration.TimeUnits.TIME_UNITS_HOURS);
        assertThat(d.getValue()).isEqualTo(1f);
    }

    @Test
    void oneDaySerialisesAsDays() {
        Registration.Duration d = StatusIntervals.fromJavaDuration(Duration.ofDays(1));
        assertThat(d.getUnits()).isEqualTo(Registration.TimeUnits.TIME_UNITS_DAYS);
        assertThat(d.getValue()).isEqualTo(1f);
    }

    @Test
    void twoHundredFiftyMillisSerialisesAsMilliseconds() {
        Registration.Duration d = StatusIntervals.fromJavaDuration(Duration.ofMillis(250));
        assertThat(d.getUnits()).isEqualTo(Registration.TimeUnits.TIME_UNITS_MILLISECONDS);
        assertThat(d.getValue()).isEqualTo(250f);
    }

    @Test
    void ninetySecondsSerialisesAsSecondsNotFractionalMinutes() {
        // 90 s is not a whole-minute multiple, so we don't lose precision by
        // downgrading. Should stay SECONDS.
        Registration.Duration d = StatusIntervals.fromJavaDuration(Duration.ofSeconds(90));
        assertThat(d.getUnits()).isEqualTo(Registration.TimeUnits.TIME_UNITS_SECONDS);
        assertThat(d.getValue()).isEqualTo(90f);
    }

    @Test
    void negativeDurationRejected() {
        assertThatThrownBy(() -> StatusIntervals.fromJavaDuration(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullJavaDurationRejected() {
        assertThatThrownBy(() -> StatusIntervals.fromJavaDuration(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundtripJavaProtoJava() {
        Duration[] samples = {
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                Duration.ofHours(1),
                Duration.ofMillis(250),
                Duration.ofSeconds(90),
        };
        for (Duration original : samples) {
            Registration.Duration wire = StatusIntervals.fromJavaDuration(original);
            Duration back = StatusIntervals.toJavaDuration(wire);
            assertThat(back).as("roundtrip %s", original).isEqualTo(original);
        }
    }

    private static Registration.Duration build(Registration.TimeUnits units, float value) {
        return Registration.Duration.newBuilder().setUnits(units).setValue(value).build();
    }
}
