/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import com.mdudel.sapient.core.protocol.HandshakeState;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-connection state tracked by a fusion-side {@link SapientReceiver}
 * when {@code enforceHandshake=true}.
 *
 * <p>One instance is created per accepted TCP connection when the peer
 * successfully registers. The receiver's stale-sweep task consults
 * {@link #isStale(Instant)} to decide when to close the socket per
 * BSI Flex 335 v2.0 §6.3.2.1 ("If no status messages are received by a
 * fusion node within three status intervals... the fusion node shall close
 * the socket connection").
 *
 * <p>Instances are not thread-safe on their own — the receiver's session
 * registry guards access via its own {@code synchronized} maps.
 */
public final class SapientFusionSession {

    private final String nodeId;
    private final Duration declaredStatusInterval;
    private final Instant registeredAt;

    private HandshakeState state;
    private Instant lastStatusAt;

    public SapientFusionSession(String nodeId, Duration declaredStatusInterval) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.declaredStatusInterval = Objects.requireNonNull(declaredStatusInterval, "declaredStatusInterval");
        if (declaredStatusInterval.isZero() || declaredStatusInterval.isNegative()) {
            throw new IllegalArgumentException("declaredStatusInterval must be positive (got "
                    + declaredStatusInterval + ")");
        }
        this.registeredAt = Instant.now();
        this.state = HandshakeState.REGISTERED;
        this.lastStatusAt = registeredAt;
    }

    public String nodeId() { return nodeId; }
    public Duration declaredStatusInterval() { return declaredStatusInterval; }
    public Instant registeredAt() { return registeredAt; }
    public HandshakeState state() { return state; }
    public Instant lastStatusAt() { return lastStatusAt; }

    /** Called on every inbound StatusReport for this peer. */
    public void markStatusReceived() { this.lastStatusAt = Instant.now(); }

    /** Peer sent StatusReport.system=GOODBYE — clean shutdown in progress. */
    public void markGoodbye() { this.state = HandshakeState.GOODBYE; }

    public void markClosed() { this.state = HandshakeState.CLOSED; }

    /**
     * True if this session hasn't heard a StatusReport in more than 3× the
     * declared status_interval (BSI Flex 335 v2.0 §6.3.2.1).
     *
     * @param now the current instant (injectable for tests).
     */
    public boolean isStale(Instant now) {
        Duration since = Duration.between(lastStatusAt, now);
        return since.compareTo(declaredStatusInterval.multipliedBy(3)) > 0;
    }

    /** Total duration this session has been REGISTERED. */
    public Duration uptime(Instant now) {
        return Duration.between(registeredAt, now);
    }

    @Override
    public String toString() {
        return "SapientFusionSession{nodeId=" + nodeId
                + ", state=" + state
                + ", statusInterval=" + declaredStatusInterval
                + ", lastStatus=" + lastStatusAt + "}";
    }
}
