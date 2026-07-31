/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.gen;

import com.mdudel.sapient.core.factory.MessageFactory;
import com.mdudel.sapient.core.geo.Geo;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Periodically emits {@link SapientMessage} {@code DetectionReport} messages
 * for a fleet of simulated tracks.
 *
 * <p>Configuration is captured in {@link Config}. Each track has a stable
 * {@code object_id} (UUID) that persists across ticks so downstream consumers
 * see the SAPIENT-standard "same track, updated position" pattern rather than
 * an ephemeral cloud of one-off detections.
 *
 * <p><b>Motion model when {@code moving}=true:</b>
 * <ul>
 *   <li>Each tick, each track advances {@code speed_m_s * (rate_ms / 1000)}
 *       metres in its current heading.</li>
 *   <li>Heading wobbles by up to ±{@code turnJitterDeg}° per tick — gives
 *       tracks momentum and a natural-looking random walk.</li>
 *   <li>If a track drifts beyond {@code 3 * radius} from the center, its
 *       heading is nudged back toward the center so it doesn't wander off
 *       forever.</li>
 * </ul>
 *
 * <p><b>Threading:</b> the send callback runs on the generator's internal
 * scheduled-executor thread — <b>not</b> the UI thread. Callers who need to
 * update UI state must marshal to the UI thread themselves
 * (e.g. {@code Platform.runLater(...)}).
 */
public final class DetectionGenerator implements AutoCloseable {

    /** Immutable configuration for a generator run. */
    public static final class Config {
        public final String nodeId;
        public final int trackCount;
        public final double centerLat;
        public final double centerLon;
        public final double radiusMeters;
        public final long tickMs;
        public final boolean moving;
        public final double speedMps;
        public final double turnJitterDeg;
        public final String classification;
        public final float confidence;

        public Config(String nodeId,
                      int trackCount,
                      double centerLat, double centerLon,
                      double radiusMeters,
                      long tickMs,
                      boolean moving,
                      double speedMps,
                      double turnJitterDeg,
                      String classification,
                      float confidence) {
            this.nodeId = nodeId;
            this.trackCount = trackCount;
            this.centerLat = centerLat;
            this.centerLon = centerLon;
            this.radiusMeters = radiusMeters;
            this.tickMs = tickMs;
            this.moving = moving;
            this.speedMps = speedMps;
            this.turnJitterDeg = turnJitterDeg;
            this.classification = classification;
            this.confidence = confidence;
        }
    }

    /** Per-track live state (mutable). */
    private static final class Track {
        final String objectId = MessageFactory.newUlid();
        double lat;
        double lon;
        double headingRad;   // 0 = north, increases clockwise (like a compass)

        Track(double lat, double lon, double headingRad) {
            this.lat = lat;
            this.lon = lon;
            this.headingRad = headingRad;
        }
    }

    private final Config config;
    private final Consumer<SapientMessage> sink;
    private final Random rnd;
    private final List<Track> tracks = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    /**
     * @param config the run configuration
     * @param sink   invoked for every generated {@link SapientMessage}
     *               (one per track per tick); called on the scheduler thread
     */
    public DetectionGenerator(Config config, Consumer<SapientMessage> sink) {
        this.config = config;
        this.sink = sink;
        this.rnd = new Random();
    }

    /** Alternate constructor with an explicit random seed — useful for tests. */
    public DetectionGenerator(Config config, Consumer<SapientMessage> sink, long seed) {
        this.config = config;
        this.sink = sink;
        this.rnd = new Random(seed);
    }

    public Config config() { return config; }

    public synchronized boolean isRunning() {
        return tickTask != null && !tickTask.isDone();
    }

    /**
     * Begin generating. Places {@code trackCount} tracks randomly in a circle
     * of {@code radiusMeters} around the center, then schedules a periodic
     * tick every {@code tickMs} milliseconds until {@link #stop()} is called.
     */
    public synchronized void start() {
        if (isRunning()) {
            throw new IllegalStateException("DetectionGenerator already running");
        }
        // Seed the fleet
        tracks.clear();
        for (int i = 0; i < config.trackCount; i++) {
            double[] p = Geo.randomPointInCircle(config.centerLat, config.centerLon,
                    config.radiusMeters, rnd);
            double heading = rnd.nextDouble() * 2 * Math.PI;
            tracks.add(new Track(p[0], p[1], heading));
        }
        // First tick immediately, then every tickMs
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "detection-gen-" + config.nodeId);
            t.setDaemon(true);
            return t;
        });
        tickTask = scheduler.scheduleAtFixedRate(this::tick, 0, config.tickMs,
                TimeUnit.MILLISECONDS);
    }

    /** Stop and release resources. */
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

    /** One periodic tick — advances motion (if enabled) then emits detections. */
    private void tick() {
        try {
            double moveMeters = config.moving
                    ? config.speedMps * (config.tickMs / 1000.0)
                    : 0.0;

            for (Track tr : tracks) {
                if (config.moving) {
                    // Jitter the heading
                    double jitterRad = Math.toRadians(
                            (rnd.nextDouble() * 2.0 - 1.0) * config.turnJitterDeg);
                    tr.headingRad += jitterRad;

                    // Corral runaway tracks: if outside 3x radius, nudge back toward center
                    double distFromCenter = Geo.distanceMeters(
                            config.centerLat, config.centerLon, tr.lat, tr.lon);
                    if (distFromCenter > config.radiusMeters * 3) {
                        double bearingToCenter = Geo.bearingRadians(
                                tr.lat, tr.lon, config.centerLat, config.centerLon);
                        // Blend heading 30% toward center each tick when out-of-bounds
                        tr.headingRad = angleLerp(tr.headingRad, bearingToCenter, 0.30);
                    }

                    // Advance: heading is a compass bearing (0 = north, CW)
                    double dEast = moveMeters * Math.sin(tr.headingRad);
                    double dNorth = moveMeters * Math.cos(tr.headingRad);
                    double[] newP = Geo.offset(tr.lat, tr.lon, dEast, dNorth);
                    tr.lat = newP[0];
                    tr.lon = newP[1];
                }

                // Emit
                SapientMessage msg = MessageFactory.detectionReport(
                        config.nodeId,
                        tr.objectId,
                        tr.lat, tr.lon,
                        null,   // no altitude
                        config.confidence,
                        config.classification);
                sink.accept(msg);
            }
        } catch (Throwable t) {
            // Swallow so ScheduledExecutorService doesn't silently kill future ticks.
            // Real listeners are already error-handled by sinks; this is belt-and-braces.
        }
    }

    /** Interpolate between two angles the short way around. */
    private static double angleLerp(double a, double b, double t) {
        double diff = Math.atan2(Math.sin(b - a), Math.cos(b - a));
        return a + diff * t;
    }
}
