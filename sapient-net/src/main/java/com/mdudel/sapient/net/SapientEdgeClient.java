/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import com.mdudel.sapient.core.factory.MessageFactory;
import com.mdudel.sapient.core.protocol.HandshakeState;
import com.mdudel.sapient.core.protocol.NodeIdentity;
import com.mdudel.sapient.core.protocol.StatusIntervals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A spec-conformant SAPIENT edge-node client that wraps a plain
 * {@link SapientTransmitter} and enforces the full handshake defined in
 * BSI Flex 335 v2.0 (§4.4, §6.2, §6.3.1, §6.3.2, §4.8, §4.9).
 *
 * <p>Lifecycle when {@link #start()} is called:
 * <ol>
 *   <li>Open TCP to the peer.</li>
 *   <li>Send {@code Registration} (§6.2.1). Move to {@link HandshakeState#REGISTERING}.</li>
 *   <li>Wait up to {@link #registrationAckTimeout} for a {@code RegistrationAck}.
 *       If none arrives, close the socket and reconnect per §4.9.
 *       (§6.2.2 gives a 30 s upper bound.)</li>
 *   <li>On {@code acceptance=true}: move to {@link HandshakeState#REGISTERED},
 *       immediately send the initial {@code StatusReport} (§6.3.1), and
 *       schedule periodic {@code StatusReport} heartbeats at the declared
 *       {@code status_interval} (§6.3.2).</li>
 *   <li>On {@code acceptance=false}: move to {@link HandshakeState#REJECTED},
 *       close the socket, and stop retrying.</li>
 * </ol>
 *
 * <p>Message sending is gated by {@link HandshakeState#canSend(String)}:
 * <ul>
 *   <li>Only {@code Registration} may cross the wire before {@code REGISTERED}.</li>
 *   <li>User-supplied {@code send} calls throw {@link IllegalStateException} if
 *       invoked from a state that does not permit that content type.</li>
 *   <li>{@code Registration} is <b>never</b> user-sendable — it is fully owned
 *       by this class.</li>
 * </ul>
 *
 * <p>On socket loss (§4.9): retries every {@link #reconnectInterval} (default
 * 10 s). If the socket comes back within {@link #reregisterAfter} (default
 * 2 min) of the disconnect, we skip Registration and resume with the same
 * {@link NodeIdentity} (same UUID). If the outage is longer, we re-register
 * with the same UUID (spec is silent on rotating; keeping identity stable is
 * the sensible default for a real sensor).
 *
 * <p>On {@link #close()}: sends a {@code StatusReport} with
 * {@code system=SYSTEM_GOODBYE} (§4.8), cancels the heartbeat scheduler,
 * and closes the socket cleanly.
 *
 * <p>Thread-safety: all state changes happen inside {@code synchronized}
 * blocks on this instance. Netty I/O callbacks land on Netty threads;
 * scheduled work lands on a single-thread {@code ScheduledExecutorService}
 * owned by this client. Callers may invoke {@link #send(SapientMessage)},
 * {@link #start()}, and {@link #close()} from any thread.
 *
 * <h2>Callbacks</h2>
 * The client exposes a lightweight {@link Listener} for handshake state
 * transitions and inbound business messages. Registration/ack traffic is
 * <em>not</em> forwarded to the listener — it is fully internal.
 */
public final class SapientEdgeClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SapientEdgeClient.class);

    /** Default RegistrationAck timeout per BSI Flex 335 v2.0 §6.2.2. */
    public static final Duration DEFAULT_REGISTRATION_ACK_TIMEOUT = Duration.ofSeconds(30);

    /** Default reconnect interval per BSI Flex 335 v2.0 §4.9. */
    public static final Duration DEFAULT_RECONNECT_INTERVAL = Duration.ofSeconds(10);

    /** Default re-registration threshold per BSI Flex 335 v2.0 §4.9. */
    public static final Duration DEFAULT_REREGISTER_AFTER = Duration.ofMinutes(2);

    private final String name;
    private final String host;
    private final int port;
    private final NodeIdentity identity;
    private final Supplier<SapientMessage> registrationSupplier;
    private final Supplier<SapientMessage> statusReportSupplier;
    private final Listener listener;
    private final Duration registrationAckTimeout;
    private final Duration reconnectInterval;
    private final Duration reregisterAfter;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sapient-edge-client");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<HandshakeState> state = new AtomicReference<>(HandshakeState.NEW);

    // All the following are guarded by `this` monitor.
    private SapientTransmitter transmitter;
    private CompletableFuture<RegistrationAck> pendingAck;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> ackTimeoutTask;
    private Instant lastDisconnectAt;
    private boolean closed;

    /** Public builder — see {@link #builder(String, int, NodeIdentity)}. */
    public static Builder builder(String host, int port, NodeIdentity identity) {
        return new Builder(host, port, identity);
    }

    private SapientEdgeClient(Builder b) {
        this.name = b.name != null ? b.name : "edge-" + b.identity.nodeId().substring(0, 8);
        this.host = Objects.requireNonNull(b.host, "host");
        this.port = b.port;
        this.identity = Objects.requireNonNull(b.identity, "identity");
        this.registrationSupplier = b.registrationSupplier != null
                ? b.registrationSupplier
                : () -> MessageFactory.registration(identity.nodeId(),
                        identity.nodeType(),
                        name);
        this.statusReportSupplier = b.statusReportSupplier != null
                ? b.statusReportSupplier
                : () -> MessageFactory.statusReport(identity.nodeId(),
                        StatusReport.System.SYSTEM_OK,
                        "default",
                        null, null, null,
                        null, null, null);
        this.listener = b.listener != null ? b.listener : new Listener() { };
        this.registrationAckTimeout = b.registrationAckTimeout != null
                ? b.registrationAckTimeout : DEFAULT_REGISTRATION_ACK_TIMEOUT;
        this.reconnectInterval = b.reconnectInterval != null
                ? b.reconnectInterval : DEFAULT_RECONNECT_INTERVAL;
        this.reregisterAfter = b.reregisterAfter != null
                ? b.reregisterAfter : DEFAULT_REREGISTER_AFTER;
    }

    public String name() { return name; }
    public String host() { return host; }
    public int port() { return port; }
    public NodeIdentity identity() { return identity; }
    public HandshakeState state() { return state.get(); }

    /**
     * Begin the handshake. Blocks until either {@link HandshakeState#REGISTERED},
     * {@link HandshakeState#REJECTED}, or the RegistrationAck timeout expires.
     *
     * @throws IllegalStateException if already started or closed.
     * @throws TimeoutException      if no RegistrationAck arrives within
     *                               {@link #registrationAckTimeout}.
     * @throws Exception             if the underlying transport fails to connect.
     */
    public void start() throws Exception {
        synchronized (this) {
            if (closed) throw new IllegalStateException("Client '" + name + "' is closed");
            if (state.get() != HandshakeState.NEW) {
                throw new IllegalStateException("Client '" + name + "' has already been started (state="
                        + state.get() + ")");
            }
        }
        connectAndRegister();
    }

    /**
     * Send an application-level message (StatusReport / DetectionReport /
     * TaskAck / Alert / AlertAck / Error). Registration and RegistrationAck
     * are owned by the client itself and rejected here.
     *
     * @throws IllegalStateException if the current handshake state forbids
     *                               sending this content type.
     */
    public void send(SapientMessage msg) {
        Objects.requireNonNull(msg, "msg");
        SapientMessage.ContentCase c = msg.getContentCase();
        if (c == SapientMessage.ContentCase.REGISTRATION
                || c == SapientMessage.ContentCase.REGISTRATION_ACK) {
            throw new IllegalStateException(
                    "Registration/RegistrationAck are owned by SapientEdgeClient; "
                            + "do not send them directly");
        }
        HandshakeState s = state.get();
        if (!s.canSend(c.name())) {
            throw new IllegalStateException("Cannot send " + c.name()
                    + " in state " + s + " (BSI Flex 335 v2.0 §6.2.2)");
        }
        synchronized (this) {
            if (transmitter == null || !transmitter.isConnected()) {
                throw new IllegalStateException("Client '" + name + "' is not connected");
            }
            transmitter.send(msg);
        }
    }

    /**
     * Graceful shutdown: sends a GOODBYE StatusReport (§4.8) if currently
     * REGISTERED, then closes the socket and cancels scheduled work. Safe
     * to call multiple times; safe to call from any state.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            cancelAckTimeout();
            cancelHeartbeat();
            if (state.get() == HandshakeState.REGISTERED
                    && transmitter != null && transmitter.isConnected()) {
                try {
                    SapientMessage goodbye = MessageFactory.statusReport(identity.nodeId(),
                            StatusReport.System.SYSTEM_GOODBYE,
                            "default",
                            null, null, null,
                            null, null, null);
                    transmitter.send(goodbye);
                    state.set(HandshakeState.GOODBYE);
                    // Give the write a short moment to flush before we shut the socket.
                    try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } catch (Exception e) {
                    log.debug("[{}] GOODBYE send failed: {}", name, e.toString());
                }
            }
            if (transmitter != null) {
                try { transmitter.close(); } catch (Exception ignored) { }
                transmitter = null;
            }
            state.set(HandshakeState.CLOSED);
        }
        scheduler.shutdownNow();
    }

    // ── Internal ────────────────────────────────────────────────────────

    /**
     * True if the client is in the REGISTERED state AND actively connected.
     * Useful for UI / test predicates.
     */
    public boolean isRegistered() {
        synchronized (this) {
            return state.get() == HandshakeState.REGISTERED
                    && transmitter != null && transmitter.isConnected();
        }
    }

    private void connectAndRegister() throws Exception {
        synchronized (this) {
            transmitter = new SapientTransmitter(name, host, port, new InternalListener());
        }
        transmitter.connect();

        boolean skipRegistration;
        synchronized (this) {
            Instant lastDown = lastDisconnectAt;
            skipRegistration = lastDown != null
                    && Duration.between(lastDown, Instant.now()).compareTo(reregisterAfter) < 0
                    && state.get() == HandshakeState.REGISTERED;
        }

        if (skipRegistration) {
            log.info("[{}] Reconnected within {}s of last disconnect — skipping re-registration (§4.9)",
                    name, reregisterAfter.getSeconds());
            fireStateChanged(HandshakeState.REGISTERED);
            scheduleHeartbeat();
            return;
        }

        // Fresh handshake.
        state.set(HandshakeState.NEW);
        fireStateChanged(HandshakeState.NEW);
        CompletableFuture<RegistrationAck> ackFuture = new CompletableFuture<>();
        synchronized (this) { pendingAck = ackFuture; }

        // Send Registration.
        SapientMessage regMsg = registrationSupplier.get();
        if (regMsg.getContentCase() != SapientMessage.ContentCase.REGISTRATION) {
            throw new IllegalStateException(
                    "registrationSupplier returned a non-Registration message: "
                            + regMsg.getContentCase());
        }
        transmitter.send(regMsg);
        state.set(HandshakeState.REGISTERING);
        fireStateChanged(HandshakeState.REGISTERING);
        log.info("[{}] Registration sent (nodeId={})", name, identity.nodeId());

        // Arm the ack timeout.
        scheduleAckTimeout(ackFuture);

        RegistrationAck ack;
        try {
            ack = ackFuture.get(registrationAckTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("[{}] RegistrationAck timeout after {}s", name, registrationAckTimeout.getSeconds());
            state.set(HandshakeState.CLOSED);
            fireStateChanged(HandshakeState.CLOSED);
            try { transmitter.close(); } catch (Exception ignore) { }
            throw te;
        }
        cancelAckTimeout();

        if (!ack.getAcceptance()) {
            log.warn("[{}] Registration rejected: {}", name,
                    ack.getAckResponseReasonCount() > 0
                            ? ack.getAckResponseReasonList()
                            : "<no reason given>");
            state.set(HandshakeState.REJECTED);
            fireStateChanged(HandshakeState.REJECTED);
            try { transmitter.close(); } catch (Exception ignore) { }
            return;
        }

        state.set(HandshakeState.REGISTERED);
        fireStateChanged(HandshakeState.REGISTERED);
        log.info("[{}] Registered — sending initial StatusReport (§6.3.1)", name);

        // Initial StatusReport (§6.3.1) — must precede any DetectionReport.
        SapientMessage initialStatus = statusReportSupplier.get();
        transmitter.send(initialStatus);

        // Schedule periodic heartbeats at declared interval.
        scheduleHeartbeat();
    }

    private void scheduleAckTimeout(CompletableFuture<RegistrationAck> ackFuture) {
        synchronized (this) {
            cancelAckTimeout();
            ackTimeoutTask = scheduler.schedule(() -> {
                if (!ackFuture.isDone()) {
                    ackFuture.completeExceptionally(
                            new TimeoutException("No RegistrationAck within "
                                    + registrationAckTimeout.getSeconds() + "s"));
                }
            }, registrationAckTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void cancelAckTimeout() {
        synchronized (this) {
            if (ackTimeoutTask != null) {
                ackTimeoutTask.cancel(false);
                ackTimeoutTask = null;
            }
        }
    }

    private void scheduleHeartbeat() {
        synchronized (this) {
            cancelHeartbeat();
            long periodMs = identity.statusInterval().toMillis();
            heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                    periodMs, periodMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelHeartbeat() {
        synchronized (this) {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
                heartbeatTask = null;
            }
        }
    }

    private void sendHeartbeat() {
        try {
            if (state.get() != HandshakeState.REGISTERED) return;
            SapientMessage msg = statusReportSupplier.get();
            synchronized (this) {
                if (transmitter != null && transmitter.isConnected()) {
                    transmitter.send(msg);
                }
            }
        } catch (Throwable t) {
            log.warn("[{}] Heartbeat send failed: {}", name, t.toString());
        }
    }

    private void scheduleReconnect() {
        if (closed) return;
        scheduler.schedule(() -> {
            try {
                connectAndRegister();
            } catch (Exception e) {
                log.warn("[{}] Reconnect failed ({}): retrying in {}s (§4.9)",
                        name, e.toString(), reconnectInterval.getSeconds());
                scheduleReconnect();
            }
        }, reconnectInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void handleAck(SapientMessage msg) {
        RegistrationAck ack = msg.getRegistrationAck();
        synchronized (this) {
            if (pendingAck != null && !pendingAck.isDone()) {
                pendingAck.complete(ack);
                pendingAck = null;
            }
        }
    }

    private void fireStateChanged(HandshakeState s) {
        try { listener.onStateChanged(s); } catch (Throwable t) {
            log.warn("[{}] listener.onStateChanged threw: {}", name, t.toString());
        }
    }

    /** Netty listener the internal transmitter uses. */
    private final class InternalListener implements SapientMessageListener {
        @Override
        public void onConnected(SocketAddress peer) {
            log.debug("[{}] socket connected to {}", name, peer);
        }
        @Override
        public void onDisconnected(SocketAddress peer) {
            log.debug("[{}] socket disconnected from {}", name, peer);
            synchronized (SapientEdgeClient.this) {
                lastDisconnectAt = Instant.now();
                cancelHeartbeat();
            }
            // Cancel any pending ack — reconnect logic will re-arm.
            synchronized (SapientEdgeClient.this) {
                if (pendingAck != null && !pendingAck.isDone()) {
                    pendingAck.completeExceptionally(new IllegalStateException("socket closed"));
                    pendingAck = null;
                }
            }
            if (!closed && state.get() != HandshakeState.REJECTED) {
                scheduleReconnect();
            }
        }
        @Override
        public void onMessage(SocketAddress peer, SapientMessage message) {
            SapientMessage.ContentCase c = message.getContentCase();
            if (c == SapientMessage.ContentCase.REGISTRATION_ACK) {
                handleAck(message);
                return;
            }
            // Everything else is delivered to the user listener.
            try { listener.onMessage(peer, message); } catch (Throwable t) {
                log.warn("[{}] listener.onMessage threw: {}", name, t.toString());
            }
        }
        @Override
        public void onError(SocketAddress peer, Throwable cause) {
            log.debug("[{}] socket error from {}: {}", name, peer, cause.toString());
            try { listener.onError(peer, cause); } catch (Throwable t) {
                log.warn("[{}] listener.onError threw: {}", name, t.toString());
            }
        }
    }

    /**
     * Handshake-aware listener contract. Callbacks land on Netty I/O threads
     * or the client's internal scheduler thread — do not block.
     */
    public interface Listener {
        /** Handshake state transitioned. */
        default void onStateChanged(HandshakeState newState) { }
        /** A non-handshake business message arrived from the peer (Task, AlertAck, Error, ...). */
        default void onMessage(SocketAddress peer, SapientMessage message) { }
        /** Socket-level error. */
        default void onError(SocketAddress peer, Throwable cause) { }
    }

    /** Fluent builder. */
    public static final class Builder {
        private String name;
        private final String host;
        private final int port;
        private final NodeIdentity identity;
        private Supplier<SapientMessage> registrationSupplier;
        private Supplier<SapientMessage> statusReportSupplier;
        private Listener listener;
        private Duration registrationAckTimeout;
        private Duration reconnectInterval;
        private Duration reregisterAfter;

        private Builder(String host, int port, NodeIdentity identity) {
            this.host = host;
            this.port = port;
            this.identity = identity;
        }

        public Builder name(String v) { this.name = v; return this; }
        public Builder registrationSupplier(Supplier<SapientMessage> v) { this.registrationSupplier = v; return this; }
        public Builder statusReportSupplier(Supplier<SapientMessage> v) { this.statusReportSupplier = v; return this; }
        public Builder listener(Listener v) { this.listener = v; return this; }
        public Builder registrationAckTimeout(Duration v) { this.registrationAckTimeout = v; return this; }
        public Builder reconnectInterval(Duration v) { this.reconnectInterval = v; return this; }
        public Builder reregisterAfter(Duration v) { this.reregisterAfter = v; return this; }

        public SapientEdgeClient build() {
            return new SapientEdgeClient(this);
        }
    }

    /** Convenience: convert a Registration.Duration to a Java Duration (delegates to core). */
    public static Duration toJavaDuration(Registration.Duration d) {
        return StatusIntervals.toJavaDuration(d);
    }
}
