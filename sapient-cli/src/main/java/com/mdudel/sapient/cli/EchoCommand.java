/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.cli;

import com.mdudel.sapient.core.validation.SapientMessageValidator;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientReceiver;
import io.netty.channel.Channel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * TCP echo server for basic wire-compat testing: accepts a SapientMessage and
 * echoes it straight back. Handy for verifying framing + serialisation are stable
 * end-to-end.
 *
 * <p>Note: v0.1 uses the {@link SapientReceiver} plumbing which does not yet
 * expose per-connection reply plumbing directly. Echo is implemented for now by
 * a lightweight follow-up in v0.2 that adds ChannelGroup broadcast/reply.
 * Until then, use {@code receive} + {@code send} in two terminals as a two-sided
 * smoke test.
 */
@Command(
        name = "echo",
        mixinStandardHelpOptions = true,
        description = "Placeholder echo server (v0.2). Use 'receive' + 'send' for v0.1 tests.")
public final class EchoCommand implements Callable<Integer> {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @Option(names = {"-p", "--port"}, required = true, description = "TCP port to listen on.")
    private int port;

    @Override
    public Integer call() throws Exception {
        System.out.printf("[%s] echo not implemented in v0.1 — use `receive` and `send` "
                + "in two shells instead. See README.%n", LocalTime.now().format(TS));
        // Suppress unused warnings for future implementer.
        SapientMessage.getDefaultInstance();
        SapientMessageValidator.describe(SapientMessage.getDefaultInstance());
        Class<?> unused1 = SapientMessageListener.class;
        Class<?> unused2 = SapientReceiver.class;
        Class<?> unused3 = Channel.class;
        Class<?> unused4 = SocketAddress.class;
        Class<?> unused5 = CountDownLatch.class;
        return 0;
    }
}
