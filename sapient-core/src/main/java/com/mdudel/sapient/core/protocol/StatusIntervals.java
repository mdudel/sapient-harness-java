/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.protocol;

import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;

import java.time.Duration;

/**
 * Conversion helpers for {@link Registration.Duration} — the protobuf
 * {units enum + float value} pair that BSI Flex 335 uses everywhere a
 * duration is declared (Registration.StatusDefinition.status_interval,
 * various mode timeouts, etc).
 *
 * <p>The protobuf shape is unfortunately trivial to mis-use — a builder that
 * forgets to set the units enum defaults to {@code TIME_UNITS_UNSPECIFIED},
 * which the spec (§4.2 Note 3) declares invalid. These helpers fail loud
 * when that happens.
 */
public final class StatusIntervals {

    private StatusIntervals() { }

    /**
     * Convert a SAPIENT {@link Registration.Duration} to a Java {@link Duration}.
     *
     * @throws IllegalArgumentException if units are unspecified or negative.
     */
    public static Duration toJavaDuration(Registration.Duration d) {
        if (d == null) {
            throw new IllegalArgumentException("Duration is null");
        }
        Registration.TimeUnits units = d.getUnits();
        if (units == Registration.TimeUnits.TIME_UNITS_UNSPECIFIED
                || units == Registration.TimeUnits.UNRECOGNIZED) {
            throw new IllegalArgumentException(
                    "Duration.units must be set to a real unit; got " + units
                            + " (see BSI Flex 335 §4.2 Note 3 — zero enums are invalid)");
        }
        float value = d.getValue();
        if (value < 0f || Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(
                    "Duration.value must be a non-negative finite float; got " + value);
        }
        // Convert everything through nanoseconds so we don't drop sub-second precision
        // for MILLISECONDS/MICROSECONDS/NANOSECONDS values.
        long nanos = switch (units) {
            case TIME_UNITS_NANOSECONDS  -> (long) value;
            case TIME_UNITS_MICROSECONDS -> (long) (value * 1_000d);
            case TIME_UNITS_MILLISECONDS -> (long) (value * 1_000_000d);
            case TIME_UNITS_SECONDS      -> (long) (value * 1_000_000_000d);
            case TIME_UNITS_MINUTES      -> (long) (value * 60d * 1_000_000_000d);
            case TIME_UNITS_HOURS        -> (long) (value * 3_600d * 1_000_000_000d);
            case TIME_UNITS_DAYS         -> (long) (value * 86_400d * 1_000_000_000d);
            default -> throw new IllegalArgumentException("Unhandled TimeUnits: " + units);
        };
        return Duration.ofNanos(nanos);
    }

    /** Same as {@link #toJavaDuration} but returns milliseconds directly. */
    public static long toMillis(Registration.Duration d) {
        return toJavaDuration(d).toMillis();
    }

    /**
     * Build a SAPIENT {@link Registration.Duration} from a Java {@link Duration}
     * in a lossless-preferred manner. Chooses the coarsest {@link Registration.TimeUnits}
     * that preserves the value exactly (so 5 s stays "5 SECONDS", not
     * "5000 MILLISECONDS"). Falls back to milliseconds for sub-second values,
     * and to seconds for anything else. Never returns {@code TIME_UNITS_UNSPECIFIED}.
     */
    public static Registration.Duration fromJavaDuration(Duration d) {
        if (d == null || d.isNegative()) {
            throw new IllegalArgumentException("Duration must be non-negative (got " + d + ")");
        }
        long millis = d.toMillis();
        long seconds = d.getSeconds();
        int subNanos = d.getNano();

        Registration.TimeUnits units;
        float value;
        if (subNanos == 0 && seconds % 86_400 == 0 && seconds > 0) {
            units = Registration.TimeUnits.TIME_UNITS_DAYS;
            value = seconds / 86_400f;
        } else if (subNanos == 0 && seconds % 3_600 == 0 && seconds > 0) {
            units = Registration.TimeUnits.TIME_UNITS_HOURS;
            value = seconds / 3_600f;
        } else if (subNanos == 0 && seconds % 60 == 0 && seconds > 0) {
            units = Registration.TimeUnits.TIME_UNITS_MINUTES;
            value = seconds / 60f;
        } else if (subNanos == 0) {
            units = Registration.TimeUnits.TIME_UNITS_SECONDS;
            value = seconds;
        } else {
            units = Registration.TimeUnits.TIME_UNITS_MILLISECONDS;
            value = millis;
        }
        return Registration.Duration.newBuilder()
                .setUnits(units)
                .setValue(value)
                .build();
    }
}
