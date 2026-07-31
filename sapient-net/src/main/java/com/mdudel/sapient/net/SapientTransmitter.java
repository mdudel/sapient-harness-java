/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A TCP client that connects to a peer and sends {@link SapientMessage} instances
 * using the SAPIENT wire framing. Any replies received back from the peer are
 * delivered through the {@link SapientMessageListener} — SAPIENT is bidirectional
 * on a single connection (e.g. Registration ↔ RegistrationAck).
 *
 * <p>Typical usage:
 * <pre>{@code
 * SapientTransmitter tx = new SapientTransmitter("tx-to-dmm",
 *         "10.0.0.20", 14000, listener);
 * tx.connect();
 * tx.send(myRegistrationMessage);
 * // ... later
 * tx.close();
 * }</pre>
 */
public final class SapientTransmitter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SapientTransmitter.class);

    private final String name;
    private final String host;
    private final int port;
    private final SapientMessageListener listener;

    private volatile EventLoopGroup group;
    private volatile Channel channel;

    public SapientTransmitter(String name, String host, int port,
                              SapientMessageListener listener) {
        this.name = Objects.requireNonNull(name);
        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.listener = Objects.requireNonNull(listener);
    }

    public String name() { return name; }
    public String host() { return host; }
    public int port() { return port; }
    public boolean isConnected() { return channel != null && channel.isActive(); }

    /** Establish the TCP connection. Blocks until connected or fails. */
    public synchronized void connect() throws InterruptedException {
        if (isConnected()) {
            throw new IllegalStateException("Transmitter '" + name + "' is already connected");
        }
        group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("frame", SapientMessageCodec.newFrameDecoder());
                        ch.pipeline().addLast("decode", new SapientMessageCodec.Decoder());
                        ch.pipeline().addLast("encode", new SapientMessageCodec.Encoder());
                        ch.pipeline().addLast("business", new InboundHandler(listener));
                    }
                });

        channel = bootstrap.connect(host, port).sync().channel();
        log.info("SapientTransmitter '{}' connected to {}:{}", name, host, port);
    }

    /** Send a message to the peer. Non-blocking. Returns the write future. */
    public ChannelFuture send(SapientMessage msg) {
        if (!isConnected()) {
            throw new IllegalStateException("Transmitter '" + name + "' is not connected");
        }
        return channel.writeAndFlush(msg);
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            group = null;
        }
        log.info("SapientTransmitter '{}' disconnected from {}:{}", name, host, port);
    }

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
