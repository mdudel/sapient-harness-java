/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration: bring up a real {@link SapientReceiver}, connect a
 * real {@link SapientTransmitter}, send a message, verify the receiver decoded
 * it byte-identically. Proves wire framing + protobuf round-trip work together.
 */
class SapientRoundTripTest {

    @Test
    void receiverAndTransmitterExchangeARegistration() throws Exception {
        // Pick an ephemeral port
        int port = pickFreePort();

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<SapientMessage> got = new AtomicReference<>();
        AtomicReference<SocketAddress> peerSeen = new AtomicReference<>();

        SapientMessageListener listener = new SapientMessageListener() {
            @Override
            public void onMessage(SocketAddress peer, SapientMessage msg) {
                peerSeen.set(peer);
                got.set(msg);
                received.countDown();
            }
        };

        try (SapientReceiver rx = new SapientReceiver("rx", port, listener)) {
            rx.start();

            SapientMessage outgoing = SapientMessage.newBuilder()
                    .setTimestamp(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond())
                            .build())
                    .setNodeId(UUID.randomUUID().toString())
                    .setRegistration(Registration.newBuilder().build())
                    .build();

            try (SapientTransmitter tx = new SapientTransmitter(
                    "tx", "127.0.0.1", port, new SapientMessageListener() {
                @Override
                public void onMessage(SocketAddress peer, SapientMessage msg) {
                    // no reply expected in this test
                }
            })) {
                tx.connect();
                tx.send(outgoing).sync();

                boolean ok = received.await(5, TimeUnit.SECONDS);
                assertThat(ok)
                        .as("receiver should have decoded the message within 5s")
                        .isTrue();
                assertThat(got.get())
                        .as("decoded message equals sent message (byte-identical protobuf)")
                        .isEqualTo(outgoing);
                assertThat(peerSeen.get()).isNotNull();
            }
        }
    }

    private static int pickFreePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
