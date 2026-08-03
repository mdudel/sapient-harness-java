/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.gen;

import com.mdudel.sapient.core.factory.MessageFactory;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationList;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationOrRangeBearing;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RangeBearingCone;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RangeBearingDatum;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Periodically emits SAPIENT {@code StatusReport} heartbeats for a single
 * sensor with a live-updating field of view (FOV).
 *
 * <p>Complements {@link DetectionGenerator}: DetectionGenerator says
 * <b>"here are things I've detected"</b>; SensorGenerator says <b>"here I am,
 * and here's the volume I'm currently observing."</b> They pair naturally in
 * a demo: fire a SensorGenerator to paint the coverage lobe on the receiver's
 * map, then fire a DetectionGenerator to spawn tracks inside it.
 *
 * <p><b>FOV modes</b> ({@link Config#fovMode}):
 * <ul>
 *   <li>{@link FovMode#CONE} \u2014 emits {@link RangeBearingCone} whose
 *       {@code azimuth} sweeps at {@code azimuthRateDegPerSec} (mod 360,
 *       wrap-around supported), {@code elevation} bobs at
 *       {@code elevationRateDegPerSec} (ping-pong within
 *       {@code [elevationMinDeg, elevationMaxDeg]}), extents fixed.</li>
 *   <li>{@link FovMode#POLYGON} \u2014 projects the same cone's ground footprint
 *       every tick as a triangular polygon ({@code polygonVertexCount}
 *       sides): apex at the sensor, base arc at {@code rangeMeters} out
 *       along {@code azimuth}, half-angle = {@code horizontalExtentDeg / 2}.
 *       Rotates with the boresight as the cone sweeps. FOV only, no
 *       obscuration on the wire.</li>
 *   <li>{@link FovMode#POLYGON_WITH_OBSCURATION} \u2014 same FOV polygon as
 *       {@code POLYGON}, plus the geometric complement of the LOB wedge
 *       (disc-minus-LOB, projected via
 *       {@link SensorGeometry#projectAntiLobPolygon}) shipped in
 *       {@code obscuration[0]}. Lets a receiver render green FOV + red
 *       "everything else" side-by-side without any receiver-side
 *       geometry work.</li>
 * </ul>
 *
 * <p><b>Platform motion</b>: if {@link Config#moving}, the sensor's
 * {@code node_location} advances every tick using the shared
 * {@link MotionModel}. Cone datum flips to
 * {@link RangeBearingDatum#RANGE_BEARING_DATUM_PLATFORM} so azimuth is
 * platform-relative rather than true-north-relative \u2014 receivers must be told
 * this, which the datum enum on the wire handles.
 *
 * <p><b>Info flag</b>: first tick emits {@code INFO_NEW}, subsequent ticks
 * emit {@code INFO_UNCHANGED} per SAPIENT semantics (receivers use Info to
 * distinguish a state change from a heartbeat).
 *
 * <p><b>Threading</b>: sink runs on the generator's scheduled-executor
 * thread. UI callers must marshal via {@code Platform.runLater(...)}.
 */
public final class SensorGenerator implements AutoCloseable {

    /** FOV representation on the wire. */
    public enum FovMode { CONE, POLYGON, POLYGON_WITH_OBSCURATION }

    /** Immutable configuration for one sensor. */
    public static final class Config {
        public final String nodeId;
        public final long tickMs;

        // Platform position (also motion params when moving=true)
        public final double centerLat;
        public final double centerLon;
        public final double centerAltM;
        public final boolean moving;
        public final double speedMps;
        public final double turnJitterDeg;
        /** Corral radius for the shared MotionModel (used only when moving). */
        public final double motionRadiusMeters;

        // FOV geometry (shared by CONE and POLYGON modes)
        public final FovMode fovMode;
        public final double initialAzimuthDeg;
        public final double azimuthRateDegPerSec;
        public final double initialElevationDeg;
        public final double elevationMinDeg;
        public final double elevationMaxDeg;
        public final double elevationRateDegPerSec;
        public final double rangeMeters;
        public final double horizontalExtentDeg;
        public final double verticalExtentDeg;

        // Polygon-only
        public final int polygonVertexCount;

        // Status housekeeping
        public final StatusReport.System system;
        public final String mode;
        public final Integer initialBatteryLevel;
        public final Double batteryDrainPerMin;   // null = no drain

        public Config(String nodeId,
                      long tickMs,
                      double centerLat, double centerLon, double centerAltM,
                      boolean moving,
                      double speedMps, double turnJitterDeg,
                      double motionRadiusMeters,
                      FovMode fovMode,
                      double initialAzimuthDeg, double azimuthRateDegPerSec,
                      double initialElevationDeg,
                      double elevationMinDeg, double elevationMaxDeg,
                      double elevationRateDegPerSec,
                      double rangeMeters,
                      double horizontalExtentDeg, double verticalExtentDeg,
                      int polygonVertexCount,
                      StatusReport.System system,
                      String mode,
                      Integer initialBatteryLevel,
                      Double batteryDrainPerMin) {
            this.nodeId = nodeId;
            this.tickMs = tickMs;
            this.centerLat = centerLat;
            this.centerLon = centerLon;
            this.centerAltM = centerAltM;
            this.moving = moving;
            this.speedMps = speedMps;
            this.turnJitterDeg = turnJitterDeg;
            this.motionRadiusMeters = motionRadiusMeters;
            this.fovMode = fovMode == null ? FovMode.CONE : fovMode;
            this.initialAzimuthDeg = initialAzimuthDeg;
            this.azimuthRateDegPerSec = azimuthRateDegPerSec;
            this.initialElevationDeg = initialElevationDeg;
            this.elevationMinDeg = elevationMinDeg;
            this.elevationMaxDeg = elevationMaxDeg;
            this.elevationRateDegPerSec = elevationRateDegPerSec;
            this.rangeMeters = rangeMeters;
            this.horizontalExtentDeg = horizontalExtentDeg;
            this.verticalExtentDeg = verticalExtentDeg;
            this.polygonVertexCount = Math.max(3, polygonVertexCount);
            this.system = system == null ? StatusReport.System.SYSTEM_OK : system;
            this.mode = mode == null ? "scanning" : mode;
            this.initialBatteryLevel = initialBatteryLevel;
            this.batteryDrainPerMin = batteryDrainPerMin;
        }
    }

    private final Config config;
    private final Consumer<SapientMessage> sink;
    private final Random rnd;

    // Live state
    private final MotionModel.State platform;
    private double currentAzimuthDeg;
    private double currentElevationDeg;
    private double elevationDirection = 1.0;    // ping-pong sign
    private Double currentBatteryLevel;
    private boolean firstTick = true;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    public SensorGenerator(Config config, Consumer<SapientMessage> sink) {
        this(config, sink, new Random());
    }

    public SensorGenerator(Config config, Consumer<SapientMessage> sink, long seed) {
        this(config, sink, new Random(seed));
    }

    private SensorGenerator(Config config, Consumer<SapientMessage> sink, Random rnd) {
        this.config = config;
        this.sink = sink;
        this.rnd = rnd;
        this.platform = new MotionModel.State(
                config.centerLat, config.centerLon,
                Math.toRadians(config.initialAzimuthDeg));    // heading seed = boresight
        this.currentAzimuthDeg = normalise360(config.initialAzimuthDeg);
        this.currentElevationDeg = clampElevation(config.initialElevationDeg);
        this.currentBatteryLevel = (config.initialBatteryLevel == null)
                ? null : config.initialBatteryLevel.doubleValue();
    }

    public Config config() { return config; }

    public synchronized boolean isRunning() {
        return tickTask != null && !tickTask.isDone();
    }

    public synchronized void start() {
        if (isRunning()) {
            throw new IllegalStateException("SensorGenerator already running");
        }
        firstTick = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sensor-gen-" + config.nodeId);
            t.setDaemon(true);
            return t;
        });
        tickTask = scheduler.scheduleAtFixedRate(this::tick, 0, config.tickMs,
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** One periodic tick \u2014 advance platform + FOV, emit StatusReport. */
    private void tick() {
        try {
            double dtSeconds = config.tickMs / 1000.0;

            // 1) Platform motion (shared model)
            if (config.moving) {
                MotionModel.Params mp = new MotionModel.Params(
                        config.centerLat, config.centerLon,
                        config.motionRadiusMeters,
                        config.speedMps, config.turnJitterDeg);
                MotionModel.step(platform, mp, dtSeconds, rnd);
            }

            // 2) Sweep azimuth: continuous rotation with wrap at 360.
            //    Supports negative rates (CCW rotation) and wrap-around
            //    azimuth ranges (e.g. an initial azimuth of 350 sweeping
            //    +20 deg lands cleanly at 10).
            currentAzimuthDeg = normalise360(
                    currentAzimuthDeg + config.azimuthRateDegPerSec * dtSeconds);

            // 3) Nod elevation: ping-pong within [min, max]. Guarded so a
            //    zero rate leaves elevation stable, and guarded so min==max
            //    (fixed elevation) doesn't oscillate against a zero-width band.
            if (config.elevationRateDegPerSec > 0
                    && config.elevationMaxDeg > config.elevationMinDeg) {
                currentElevationDeg += elevationDirection
                        * config.elevationRateDegPerSec * dtSeconds;
                if (currentElevationDeg >= config.elevationMaxDeg) {
                    currentElevationDeg = config.elevationMaxDeg;
                    elevationDirection = -1.0;
                } else if (currentElevationDeg <= config.elevationMinDeg) {
                    currentElevationDeg = config.elevationMinDeg;
                    elevationDirection = 1.0;
                }
            }

            // 4) Battery drain (per-minute rate * dt-minutes)
            if (currentBatteryLevel != null && config.batteryDrainPerMin != null
                    && config.batteryDrainPerMin > 0) {
                currentBatteryLevel -= config.batteryDrainPerMin * (dtSeconds / 60.0);
                if (currentBatteryLevel < 0) currentBatteryLevel = 0.0;
            }

            // 5) Build the FOV payload for this tick.
            LocationOrRangeBearing fov = buildFov();

            // 5b) POLYGON_WITH_OBSCURATION mode also emits the geometric
            //     complement of the LOB (disc-minus-LOB) as obscuration, so
            //     a receiver renders green FOV + red "everything else"
            //     side-by-side. Plain POLYGON and CONE modes ship no
            //     obscuration \u2014 they set only field_of_view on the wire.
            List<LocationOrRangeBearing> obscuration = Collections.emptyList();
            if (config.fovMode == FovMode.POLYGON_WITH_OBSCURATION) {
                List<List<double[]>> antiLob = SensorGeometry.projectAntiLobPolygon(
                        platform.lat, platform.lon,
                        currentAzimuthDeg,
                        config.horizontalExtentDeg,
                        config.rangeMeters,
                        /*arcVertices*/ Math.max(12, config.polygonVertexCount * 2));
                if (!antiLob.isEmpty()) {
                    List<LocationOrRangeBearing> obs = new ArrayList<>(antiLob.size());
                    for (List<double[]> p : antiLob) {
                        obs.add(MessageFactory.fovPolygon(
                                MessageFactory.polygon(p, config.centerAltM)));
                    }
                    obscuration = obs;
                }
            }

            // 6) Emit.
            StatusReport.Info info = firstTick
                    ? StatusReport.Info.INFO_NEW
                    : StatusReport.Info.INFO_UNCHANGED;
            firstTick = false;

            SapientMessage msg = MessageFactory.statusReport(
                    config.nodeId,
                    config.system,
                    info,
                    config.mode,
                    platform.lat, platform.lon, config.centerAltM,
                    null, null,
                    currentBatteryLevel == null ? null : currentBatteryLevel.intValue(),
                    fov,
                    Collections.emptyList(),
                    obscuration);
            sink.accept(msg);
        } catch (Throwable t) {
            // Swallow so ScheduledExecutorService doesn't silently kill future ticks.
        }
    }

    /** Build the current FOV as either a RangeBearingCone or a ground polygon. */
    private LocationOrRangeBearing buildFov() {
        RangeBearingDatum datum = config.moving
                ? RangeBearingDatum.RANGE_BEARING_DATUM_PLATFORM
                : RangeBearingDatum.RANGE_BEARING_DATUM_TRUE;

        if (config.fovMode == FovMode.CONE) {
            RangeBearingCone cone = MessageFactory.cone(
                    currentAzimuthDeg, currentElevationDeg, config.rangeMeters,
                    config.horizontalExtentDeg, config.verticalExtentDeg,
                    datum);
            return MessageFactory.fovCone(cone);
        }

        // POLYGON and POLYGON_WITH_OBSCURATION: project the cone's ground
        // footprint via the shared helper so the one-shot StatusReport
        // dialog and the live ticker stay in perfect lock-step. Both
        // polygon modes emit the identical FOV geometry \u2014 they diverge
        // only in whether tick() attaches the anti-LOB obscuration.
        // (Was originally documented as POLYGON-only; kept the code path
        // shared when POLYGON_WITH_OBSCURATION was split out 2026-08-03.)
        List<double[]> vertices = SensorGeometry.projectConeFootprint(
                platform.lat, platform.lon,
                currentAzimuthDeg,
                config.horizontalExtentDeg,
                config.rangeMeters,
                config.polygonVertexCount);
        LocationList poly = MessageFactory.polygon(vertices, config.centerAltM);
        return MessageFactory.fovPolygon(poly);
    }

    private double clampElevation(double deg) {
        if (config.elevationMaxDeg <= config.elevationMinDeg) return deg;
        return Math.max(config.elevationMinDeg,
                Math.min(config.elevationMaxDeg, deg));
    }

    private static double normalise360(double deg) {
        double r = deg % 360.0;
        if (r < 0) r += 360.0;
        return r;
    }
}
