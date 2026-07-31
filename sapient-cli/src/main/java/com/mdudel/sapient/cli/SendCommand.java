/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.cli;

import com.google.protobuf.Timestamp;
import com.mdudel.sapient.core.template.MessageTemplateLoader;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientTransmitter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Connect to a SAPIENT peer and send one (or repeated) message(s). If no template
 * file is given, sends a minimal synthesised Registration so ye can smoke-test wire
 * compatibility without any input files.
 */
@Command(
        name = "send",
        mixinStandardHelpOptions = true,
        description = "Connect to a SAPIENT peer and send a message (from JSON template or synthesised).")
public final class SendCommand implements Callable<Integer> {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @Option(names = {"-h", "--host"}, required = true, description = "Target host.")
    private String host;

    @Option(names = {"-p", "--port"}, required = true, description = "Target TCP port.")
    private int port;

    @Option(names = {"-t", "--template"},
            description = "Path to a JSON SapientMessage template. Optional — omit for a synthesised Registration.")
    private Path template;

    @Option(names = {"-r", "--repeat"}, defaultValue = "1",
            description = "Send the message this many times (default 1).")
    private int repeat;

    @Option(names = "--interval-ms", defaultValue = "1000",
            description = "Delay between repeats in milliseconds (default 1000).")
    private long intervalMs;

    @Option(names = {"-n", "--name"}, defaultValue = "transmitter",
            description = "Friendly name for the transmitter (used in log output).")
    private String name;

    @Override
    public Integer call() throws Exception {
        SapientMessage msg = (template != null)
                ? MessageTemplateLoader.fromJsonFile(template)
                : synthesiseRegistration();

        SapientMessageListener listener = new SapientMessageListener() {
            @Override
            public void onConnected(SocketAddress peer) {
                System.out.printf("[%s] %s → CONNECT %s%n",
                        LocalTime.now().format(TS), name, peer);
            }

            @Override
            public void onDisconnected(SocketAddress peer) {
                System.out.printf("[%s] %s → DISCONNECT %s%n",
                        LocalTime.now().format(TS), name, peer);
            }

            @Override
            public void onMessage(SocketAddress peer, SapientMessage sm) {
                System.out.printf("[%s] %s ← REPLY %s   %s from %s%n",
                        LocalTime.now().format(TS), name, peer,
                        sm.getContentCase().name(), sm.getNodeId());
            }

            @Override
            public void onError(SocketAddress peer, Throwable cause) {
                System.err.printf("[%s] %s ! ERROR %s: %s%n",
                        LocalTime.now().format(TS), name, peer, cause);
            }
        };

        try (SapientTransmitter tx = new SapientTransmitter(name, host, port, listener)) {
            tx.connect();
            for (int i = 0; i < repeat; i++) {
                tx.send(msg).sync();
                System.out.printf("[%s] %s → SENT %s (%d/%d)%n",
                        LocalTime.now().format(TS), name,
                        msg.getContentCase().name(), i + 1, repeat);
                if (i < repeat - 1) {
                    Thread.sleep(intervalMs);
                }
            }
            // Give any reply a moment to arrive.
            Thread.sleep(500);
        }
        return 0;
    }

    /** A minimal legal SapientMessage synthesised on the fly for smoke testing. */
    private static SapientMessage synthesiseRegistration() {
        Instant now = Instant.now();
        return SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder()
                        .setSeconds(now.getEpochSecond())
                        .setNanos(now.getNano())
                        .build())
                .setNodeId(UUID.randomUUID().toString())
                .setRegistration(Registration.newBuilder()
                        // Minimal Registration — real implementations will want much more.
                        .build())
                .build();
    }
}
