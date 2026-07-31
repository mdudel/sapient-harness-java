/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.cli;

import com.mdudel.sapient.core.template.MessageTemplateLoader;
import com.mdudel.sapient.core.validation.SapientMessageValidator;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientReceiver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Start a SAPIENT receiver (TCP server) and log everything it receives to stdout.
 * Handy for smoke-testing that an AlmondMalt (or dstl reference) transmitter sends
 * well-formed messages.
 */
@Command(
        name = "receive",
        mixinStandardHelpOptions = true,
        description = "Run a TCP server that accepts SAPIENT messages and logs them.")
public final class ReceiveCommand implements Callable<Integer> {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @Option(names = {"-p", "--port"}, required = true, description = "TCP port to listen on.")
    private int port;

    @Option(names = {"-n", "--name"}, defaultValue = "receiver",
            description = "Friendly name for the receiver (used in log output).")
    private String name;

    @Option(names = "--dump-json", description = "Also dump each received message as JSON.")
    private boolean dumpJson;

    @Override
    public Integer call() throws Exception {
        CountDownLatch shutdown = new CountDownLatch(1);

        SapientMessageListener listener = new SapientMessageListener() {
            @Override
            public void onConnected(SocketAddress peer) {
                System.out.printf("[%s] %s ← CONNECT %s%n",
                        LocalTime.now().format(TS), name, peer);
            }

            @Override
            public void onDisconnected(SocketAddress peer) {
                System.out.printf("[%s] %s ← DISCONNECT %s%n",
                        LocalTime.now().format(TS), name, peer);
            }

            @Override
            public void onMessage(SocketAddress peer, SapientMessage msg) {
                SapientMessageValidator.ValidationResult v =
                        SapientMessageValidator.validate(msg);
                System.out.printf("[%s] %s ← %s   %s%n",
                        LocalTime.now().format(TS), name, peer, v.summary());
                if (dumpJson) {
                    try {
                        System.out.println(MessageTemplateLoader.toJson(msg));
                    } catch (Exception e) {
                        System.out.println("  (JSON dump failed: " + e.getMessage() + ")");
                    }
                }
            }

            @Override
            public void onError(SocketAddress peer, Throwable cause) {
                System.err.printf("[%s] %s ← ERROR %s: %s%n",
                        LocalTime.now().format(TS), name, peer, cause);
            }
        };

        SapientReceiver rx = new SapientReceiver(name, port, listener);
        rx.start();
        System.out.printf("[%s] %s listening on port %d (Ctrl-C to stop)%n",
                LocalTime.now().format(TS), name, port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.printf("[%s] %s shutting down…%n",
                    LocalTime.now().format(TS), name);
            rx.stop();
            shutdown.countDown();
        }));

        shutdown.await();
        return 0;
    }
}
