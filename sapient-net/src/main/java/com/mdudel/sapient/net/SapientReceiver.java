/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import com.mdudel.sapient.core.factory.MessageFactory;
import com.mdudel.sapient.core.protocol.NodeIdentity;
import com.mdudel.sapient.core.protocol.StatusIntervals;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A TCP server that listens for SAPIENT messages from any number of connected peers.
 *
 * <p><b>Dumb mode</b> (default, {@code enforceHandshake=false}): decode each frame and
 * pass it to the {@link SapientMessageListener}. Legacy v0.1 behaviour, useful for
 * one-shot wire-format testing and for peers that don't speak the full lifecycle.
 *
 * <p><b>Enforce-handshake mode</b> ({@code enforceHandshake=true}): implements the
 * fusion-node side of BSI Flex 335 v2.0 (§4.4, §6.2, §6.3.2, §4.8):
 * <ul>
 *   <li>First frame from each connection <b>must</b> be a {@code Registration}.
 *       Anything else → send {@code Error} + close socket.</li>
 *   <li>On a valid Registration, immediately reply with a
 *       {@code RegistrationAck} carrying {@code acceptance=true} and
 *       {@code destination_id=<peer node_id>} (Table 1 + §6.2.2). Rejection is
 *       controlled by an injected {@link RegistrationPolicy} — default accepts
 *       everything.</li>
 *   <li>Subsequent frames from an unregistered peer → {@code Error} + close.</li>
 *   <li>{@code StatusReport} arrivals refresh the per-peer heartbeat timestamp.</li>
 *   <li>A stale-sweep task closes any peer whose most recent {@code StatusReport}
 *       is older than 3× the peer's declared {@code status_interval} (§6.3.2.1).</li>
 *   <li>{@code StatusReport.system=SYSTEM_GOODBYE} triggers a clean close and
 *       drops the peer from the registry (§4.8).</li>
 * </ul>
 *
 * <p>The user-supplied {@link SapientMessageListener} still sees every decoded
 * message — enforcement happens <em>alongside</em> the callback, not instead of it,
 * so UIs can log both the peer's traffic and the receiver's automatic replies.
 *
 * <p>Typical usage:
 * <pre>{@code
 * SapientReceiver rx = new SapientReceiver("receiver-A", 12000, listener); // dumb mode
 *
 * SapientReceiver rxStrict = SapientReceiver.builder("receiver-B", 12001)
 *         .listener(listener)
 *         .enforceHandshake(true)
 *         .selfNodeId(UUID.randomUUID().toString())
 *         .registrationPolicy(reg -> RegistrationOutcome.accept())
 *         .build();
 * rxStrict.start();
 * }</pre>
 */
public final class SapientReceiver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SapientReceiver.class);

    /** How often the stale-sweep task runs when handshake enforcement is on. */
    static final Duration STALE_SWEEP_PERIOD = Duration.ofSeconds(1);

    private final String name;
    private final int port;
    private final SapientMessageListener listener;
    private final boolean enforceHandshake;
    private final String selfNodeId;
    private final RegistrationPolicy registrationPolicy;
    /**
     * Optional spec used to derive a node_id-aware policy per Registration.
     * When set, takes precedence over {@link #registrationPolicy} because
     * only the spec path knows the envelope UUID needed for allow/deny
     * list evaluation.
     */
    private final RegistrationPolicySpec registrationPolicySpec;

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;

    /** Registry of every currently-open child channel — used for broadcast + shutdown. */
    private final ChannelGroup allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * Session state per accepted connection. Keyed on the Netty channel id
     * so we can look up quickly on inbound messages and clean up on close.
     */
    private final Map<String, SapientFusionSession> sessions = new ConcurrentHashMap<>();

    /** Reverse map: nodeId → channel, used for targeted replies to a specific peer. */
    private final Map<String, Channel> nodeChannels = new ConcurrentHashMap<>();

    private volatile ScheduledFuture<?> staleSweepTask;

    /** Legacy constructor — dumb mode, no handshake enforcement. */
    public SapientReceiver(String name, int port, SapientMessageListener listener) {
        this(name, port, listener, false, null, RegistrationPolicy.acceptAll(), null);
    }

    private SapientReceiver(String name, int port, SapientMessageListener listener,
                            boolean enforceHandshake, String selfNodeId,
                            RegistrationPolicy policy,
                            RegistrationPolicySpec spec) {
        this.name = Objects.requireNonNull(name);
        this.port = port;
        this.listener = Objects.requireNonNull(listener);
        this.enforceHandshake = enforceHandshake;
        this.selfNodeId = selfNodeId != null ? selfNodeId : UUID.randomUUID().toString();
        this.registrationPolicy = policy != null ? policy : RegistrationPolicy.acceptAll();
        this.registrationPolicySpec = spec;
        if (enforceHandshake) {
            try { UUID.fromString(this.selfNodeId); } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("selfNodeId must be a valid UUID", e);
            }
        }
    }

    public String name() { return name; }
    public int port() { return port; }
    public boolean isRunning() { return serverChannel != null && serverChannel.isActive(); }
    public boolean enforceHandshake() { return enforceHandshake; }
    public String selfNodeId() { return selfNodeId; }

    /** Snapshot of the current registered-peer registry (immutable copy). */
    public Map<String, SapientFusionSession> sessions() {
        return Map.copyOf(sessions);
    }

    /** Bind + accept. Blocks briefly until the server is bound. */
    public synchronized void start() throws InterruptedException {
        if (isRunning()) {
            throw new IllegalStateException("Receiver '" + name + "' is already running");
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("frame", SapientMessageCodec.newFrameDecoder());
                        ch.pipeline().addLast("decode", new SapientMessageCodec.Decoder());
                        ch.pipeline().addLast("encode", new SapientMessageCodec.Encoder());
                        if (enforceHandshake) {
                            ch.pipeline().addLast("handshake",
                                    new HandshakeEnforcingHandler(SapientReceiver.this));
                        }
                        ch.pipeline().addLast("business", new InboundHandler(listener, allChannels));
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("SapientReceiver '{}' listening on port {} (enforceHandshake={})",
                name, port, enforceHandshake);

        if (enforceHandshake) {
            long periodMs = STALE_SWEEP_PERIOD.toMillis();
            staleSweepTask = workerGroup.scheduleAtFixedRate(this::sweepStale,
                    periodMs, periodMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Send a message to every currently-connected peer. */
    public void broadcast(SapientMessage msg) {
        if (!isRunning()) {
            throw new IllegalStateException("Receiver '" + name + "' is not running");
        }
        allChannels.writeAndFlush(msg);
    }

    /** Send a message to a specific registered peer by nodeId. Returns false if unknown. */
    public boolean sendTo(String nodeId, SapientMessage msg) {
        Channel c = nodeChannels.get(nodeId);
        if (c == null || !c.isActive()) return false;
        c.writeAndFlush(msg);
        return true;
    }

    /** Graceful shutdown; releases threads and blocks up to 5s. */
    public synchronized void stop() {
        if (staleSweepTask != null) {
            staleSweepTask.cancel(false);
            staleSweepTask = null;
        }
        // Close every child channel first so no more inbound frames land.
        try { allChannels.close().sync(); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        sessions.clear();
        nodeChannels.clear();

        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            bossGroup = null;
        }
        log.info("SapientReceiver '{}' stopped", name);
    }

    @Override
    public void close() {
        stop();
    }

    // ── Handshake plumbing ─────────────────────────────────────────────

    /** Sweep task: close any session whose peer has gone silent past 3× status_interval. */
    private void sweepStale() {
        Instant now = Instant.now();
        for (Map.Entry<String, SapientFusionSession> e : sessions.entrySet()) {
            SapientFusionSession s = e.getValue();
            if (s.isStale(now)) {
                Channel c = nodeChannels.get(s.nodeId());
                log.info("[{}] closing stale peer nodeId={} (silent for >{}s)",
                        name, s.nodeId(), s.declaredStatusInterval().multipliedBy(3).getSeconds());
                s.markClosed();
                if (c != null) try { c.close(); } catch (Throwable ignore) { }
            }
        }
    }

    /**
     * Netty handler that enforces the SAPIENT handshake ordering per connection.
     * Sits <em>before</em> the business handler so it can suppress bad frames
     * and inject RegistrationAck / Error responses on the same channel.
     */
    private static final class HandshakeEnforcingHandler extends ChannelInboundHandlerAdapter {
        private final SapientReceiver rx;
        private volatile String peerNodeId;
        private volatile boolean registered;

        HandshakeEnforcingHandler(SapientReceiver rx) {
            this.rx = rx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof SapientMessage sm)) {
                ctx.fireChannelRead(msg);
                return;
            }
            SapientMessage.ContentCase c = sm.getContentCase();

            if (!registered) {
                if (c != SapientMessage.ContentCase.REGISTRATION) {
                    // §6.2.2: nothing may cross the wire before Registration.
                    log.warn("[{}] first frame from {} was {} (expected REGISTRATION); closing",
                            rx.name, ctx.channel().remoteAddress(), c);
                    SapientMessage err = MessageFactory.error(rx.selfNodeId,
                            "First frame must be Registration (BSI Flex 335 §6.2.2); got " + c);
                    ctx.writeAndFlush(err).addListener(f -> ctx.close());
                    return;
                }
                // Valid Registration — apply policy, ack, and register.
                // Spec path (takes precedence) knows the envelope node_id so
                // it can enforce allow/deny lists. Fall back to the plain
                // policy for callers that never set a spec.
                RegistrationOutcome outcome;
                try {
                    if (rx.registrationPolicySpec != null) {
                        outcome = rx.registrationPolicySpec
                                .toPolicy(sm.getNodeId())
                                .evaluate(sm.getRegistration());
                    } else {
                        outcome = rx.registrationPolicy.evaluate(sm.getRegistration());
                    }
                } catch (Throwable t) {
                    log.warn("[{}] registrationPolicy threw: {}", rx.name, t.toString());
                    outcome = RegistrationOutcome.reject("policy error: " + t.getMessage());
                }
                String nodeId = sm.getNodeId();
                Duration interval;
                try {
                    interval = StatusIntervals.toJavaDuration(
                            sm.getRegistration().getStatusDefinition().getStatusInterval());
                } catch (Throwable t) {
                    log.warn("[{}] Registration from {} has invalid status_interval: {}",
                            rx.name, nodeId, t.toString());
                    interval = NodeIdentity.DEFAULT_STATUS_INTERVAL;
                }

                if (outcome.accepted) {
                    peerNodeId = nodeId;
                    registered = true;
                    SapientFusionSession session = new SapientFusionSession(nodeId, interval);
                    rx.sessions.put(nodeId, session);
                    rx.nodeChannels.put(nodeId, ctx.channel());
                    SapientMessage ack = MessageFactory.registrationAck(rx.selfNodeId, nodeId,
                            /*accept*/ true, null);
                    ctx.writeAndFlush(ack);
                    log.info("[{}] Registered peer nodeId={} statusInterval={}s",
                            rx.name, nodeId, interval.getSeconds());
                    // Let the business listener also see the Registration for logging.
                    ctx.fireChannelRead(sm);
                } else {
                    log.info("[{}] Rejecting Registration from nodeId={} reason={}",
                            rx.name, nodeId, outcome.reason);
                    SapientMessage nack = MessageFactory.registrationAck(rx.selfNodeId, nodeId,
                            /*accept*/ false, outcome.reason != null ? outcome.reason : "rejected");
                    ctx.writeAndFlush(nack).addListener(f -> ctx.close());
                }
                return;
            }

            // Already registered — normal traffic. Track heartbeats + GOODBYE.
            if (c == SapientMessage.ContentCase.STATUS_REPORT) {
                SapientFusionSession s = rx.sessions.get(peerNodeId);
                if (s != null) {
                    s.markStatusReceived();
                    if (sm.getStatusReport().getSystem() == StatusReport.System.SYSTEM_GOODBYE) {
                        s.markGoodbye();
                        log.info("[{}] Peer nodeId={} sent GOODBYE — closing", rx.name, peerNodeId);
                        ctx.fireChannelRead(sm);
                        ctx.close();
                        return;
                    }
                }
            } else if (c == SapientMessage.ContentCase.REGISTRATION) {
                // Re-registration on an open connection is not expected in v2.0.
                // Spec allows a fusion node to REQUEST re-registration; a peer
                // spamming it is a bug we should surface.
                log.warn("[{}] Peer nodeId={} sent duplicate Registration on an open session",
                        rx.name, peerNodeId);
            }
            // Forward everything to the business handler for the user listener.
            ctx.fireChannelRead(sm);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (peerNodeId != null) {
                SapientFusionSession s = rx.sessions.remove(peerNodeId);
                if (s != null) s.markClosed();
                rx.nodeChannels.remove(peerNodeId);
            }
            ctx.fireChannelInactive();
        }
    }

    /** Netty inbound handler that turns decoded messages into listener callbacks. */
    private static final class InboundHandler extends ChannelInboundHandlerAdapter {
        private final SapientMessageListener listener;
        private final ChannelGroup allChannels;

        InboundHandler(SapientMessageListener listener, ChannelGroup allChannels) {
            this.listener = listener;
            this.allChannels = allChannels;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            allChannels.add(ctx.channel());
            SocketAddress peer = ctx.channel().remoteAddress();
            listener.onConnected(peer);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            SocketAddress peer = ctx.channel().remoteAddress();
            listener.onDisconnected(peer);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof SapientMessage sm) {
                listener.onMessage(ctx.channel().remoteAddress(), sm);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            listener.onError(ctx.channel().remoteAddress(), cause);
            ctx.close();
        }
    }

    // ── Builder + policy ───────────────────────────────────────────────

    public static Builder builder(String name, int port) {
        return new Builder(name, port);
    }

    /** Fluent builder — required for handshake-enforcing receivers. */
    public static final class Builder {
        private final String name;
        private final int port;
        private SapientMessageListener listener;
        private boolean enforceHandshake;
        private String selfNodeId;
        private RegistrationPolicy registrationPolicy;
        private RegistrationPolicySpec registrationPolicySpec;

        private Builder(String name, int port) {
            this.name = name;
            this.port = port;
        }

        public Builder listener(SapientMessageListener v) { this.listener = v; return this; }
        public Builder enforceHandshake(boolean v) { this.enforceHandshake = v; return this; }
        public Builder selfNodeId(String v) { this.selfNodeId = v; return this; }
        public Builder registrationPolicy(RegistrationPolicy v) { this.registrationPolicy = v; return this; }

        /**
         * Set a JSON-serialisable policy spec. Takes precedence over any
         * {@link #registrationPolicy(RegistrationPolicy)} because only the
         * spec path is envelope-node_id-aware (needed for allow/deny lists).
         */
        public Builder registrationPolicySpec(RegistrationPolicySpec v) {
            this.registrationPolicySpec = v;
            return this;
        }

        public SapientReceiver build() {
            if (listener == null) throw new IllegalStateException("listener must be set");
            return new SapientReceiver(name, port, listener, enforceHandshake, selfNodeId,
                    registrationPolicy, registrationPolicySpec);
        }
    }

    /**
     * Fusion-side policy that decides whether to accept or reject a peer's
     * Registration. Default (see {@link #acceptAll()}) accepts everything.
     * A production fusion node would typically enforce ICD version, node
     * allowlist, or capability requirements here.
     */
    @FunctionalInterface
    public interface RegistrationPolicy {
        RegistrationOutcome evaluate(Registration registration);

        static RegistrationPolicy acceptAll() {
            return reg -> RegistrationOutcome.accept();
        }

        /** Reject any peer whose ICD version doesn't match the given string. */
        static RegistrationPolicy requireIcdVersion(String required) {
            return reg -> {
                String v = reg.getIcdVersion();
                return required.equals(v)
                        ? RegistrationOutcome.accept()
                        : RegistrationOutcome.reject("Unsupported ICD version '" + v + "' (require '" + required + "')");
            };
        }

        /** Combine two policies — both must accept. */
        static RegistrationPolicy and(RegistrationPolicy a, RegistrationPolicy b) {
            return reg -> {
                RegistrationOutcome ao = a.evaluate(reg);
                if (!ao.accepted) return ao;
                return b.evaluate(reg);
            };
        }

        /**
         * Combine two policies — accept if either accepts. First-accept wins;
         * on double-reject the second reason is returned. Rarely useful in
         * practice (fusion nodes typically stack AND constraints) but handy
         * for 'allowlist A OR allowlist B' compositions.
         */
        static RegistrationPolicy or(RegistrationPolicy a, RegistrationPolicy b) {
            return reg -> {
                RegistrationOutcome ao = a.evaluate(reg);
                if (ao.accepted) return ao;
                return b.evaluate(reg);
            };
        }

        /**
         * Reject any peer whose declared {@link Registration.NodeType} is not
         * in the given allowlist. Empty varargs / null are treated as 'no
         * constraint' — the operator likely cleared the checkboxes without
         * meaning 'reject everything'.
         */
        static RegistrationPolicy requireNodeType(Registration.NodeType... allowed) {
            if (allowed == null || allowed.length == 0) return acceptAll();
            java.util.Set<Registration.NodeType> allow = java.util.Set.of(allowed);
            return reg -> {
                java.util.Set<Registration.NodeType> declared = new java.util.LinkedHashSet<>();
                for (Registration.NodeDefinition nd : reg.getNodeDefinitionList()) {
                    declared.add(nd.getNodeType());
                }
                for (Registration.NodeType t : declared) {
                    if (allow.contains(t)) return RegistrationOutcome.accept();
                }
                return RegistrationOutcome.reject("Declared node types " + declared
                        + " do not intersect allowlist " + allow);
            };
        }
    }

    /** Immutable outcome of a {@link RegistrationPolicy} evaluation. */
    public static final class RegistrationOutcome {
        public final boolean accepted;
        public final String reason;

        private RegistrationOutcome(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        public static RegistrationOutcome accept() {
            return new RegistrationOutcome(true, null);
        }

        public static RegistrationOutcome reject(String reason) {
            return new RegistrationOutcome(false, reason);
        }
    }
}
