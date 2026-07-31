/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A TCP server that listens for SAPIENT messages from any number of connected peers.
 * Each accepted connection is decoded through {@link SapientMessageCodec} and delivered
 * as {@link SapientMessage} instances to the configured {@link SapientMessageListener}.
 *
 * <p>Typical usage:
 * <pre>{@code
 * SapientReceiver rx = new SapientReceiver("receiver-A", 12000, listener);
 * rx.start();
 * // ... later
 * rx.stop();
 * }</pre>
 */
public final class SapientReceiver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SapientReceiver.class);

    private final String name;
    private final int port;
    private final SapientMessageListener listener;

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;

    public SapientReceiver(String name, int port, SapientMessageListener listener) {
        this.name = Objects.requireNonNull(name);
        this.port = port;
        this.listener = Objects.requireNonNull(listener);
    }

    public String name() { return name; }
    public int port() { return port; }
    public boolean isRunning() { return serverChannel != null && serverChannel.isActive(); }

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
                        ch.pipeline().addLast("business", new InboundHandler(listener));
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("SapientReceiver '{}' listening on port {}", name, port);
    }

    /** Send a message to every currently-connected peer. */
    public void broadcast(SapientMessage msg) {
        if (!isRunning()) {
            throw new IllegalStateException("Receiver '" + name + "' is not running");
        }
        // Cheap fan-out: iterate the worker group's live child channels via
        // Netty's ChannelGroup would be nicer; for v0.1 we walk the pipeline
        // via the parent channel's config's group. Simpler: expose broadcast
        // through a ChannelGroup managed here (added in a follow-up refactor).
        serverChannel.parent(); // no-op; broadcast is added in a follow-up
        throw new UnsupportedOperationException(
                "broadcast() is scheduled for v0.2 — see docs/ROADMAP.md");
    }

    /** Graceful shutdown; releases threads and blocks up to 5s. */
    public synchronized void stop() {
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

    /** Netty inbound handler that turns decoded messages into listener callbacks. */
    private static final class InboundHandler extends ChannelInboundHandlerAdapter {
        private final SapientMessageListener listener;

        InboundHandler(SapientMessageListener listener) {
            this.listener = listener;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
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
}
