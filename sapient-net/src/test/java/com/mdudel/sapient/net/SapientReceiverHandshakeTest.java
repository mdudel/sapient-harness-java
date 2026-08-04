/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import com.mdudel.sapient.core.factory.MessageFactory;
import com.mdudel.sapient.core.protocol.HandshakeState;
import com.mdudel.sapient.core.protocol.NodeIdentity;
import org.junit.jupiter.api.Test;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

import java.net.ServerSocket;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for {@link SapientReceiver} with {@code enforceHandshake=true}.
 * Combined with {@link SapientEdgeClient} tests, this validates the full two-sided
 * BSI Flex 335 v2.0 handshake: edge Registration → fusion RegistrationAck →
 * initial StatusReport → heartbeat cadence, plus the fusion-side stale sweep
 * (§6.3.2.1), GOODBYE handling (§4.8), and rejection of pre-Registered traffic.
 */
class SapientReceiverHandshakeTest {

    private static int pickFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    /** A recording listener — all decoded messages are appended in arrival order. */
    private static final class RecordingListener implements SapientMessageListener {
        final List<SapientMessage> received = new CopyOnWriteArrayList<>();
        final CountDownLatch registrationLatch = new CountDownLatch(1);
        final CountDownLatch statusLatch = new CountDownLatch(1);
        @Override
        public void onMessage(SocketAddress peer, SapientMessage msg) {
            received.add(msg);
            switch (msg.getContentCase()) {
                case REGISTRATION -> registrationLatch.countDown();
                case STATUS_REPORT -> statusLatch.countDown();
                default -> { }
            }
        }
    }

    @Test
    void enforcingReceiverAndEdgeClientCompleteFullHandshake() throws Exception {
        int port = pickFreePort();
        RecordingListener rxListener = new RecordingListener();
        SapientReceiver rx = SapientReceiver.builder("fusion", port)
                .listener(rxListener)
                .enforceHandshake(true)
                .build();
        rx.start();
        try {
            NodeIdentity id = NodeIdentity.newRandom(Registration.NodeType.NODE_TYPE_RADAR,
                    Duration.ofSeconds(60));
            SapientEdgeClient client = SapientEdgeClient.builder("127.0.0.1", port, id)
                    .name("edge")
                    .build();
            try {
                client.start();
                assertThat(client.state()).isEqualTo(HandshakeState.REGISTERED);
                assertThat(client.isRegistered()).isTrue();

                // Fusion side sees at least Registration + initial StatusReport.
                assertThat(rxListener.registrationLatch.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(rxListener.statusLatch.await(2, TimeUnit.SECONDS)).isTrue();

                // Fusion registry knows about the peer.
                assertThat(rx.sessions()).containsKey(id.nodeId());
                assertThat(rx.sessions().get(id.nodeId()).state())
                        .isEqualTo(HandshakeState.REGISTERED);
            } finally {
                client.close();
            }
            // Post-close: after GOODBYE, the fusion registry should drop the peer.
            Thread.sleep(300);
            assertThat(rx.sessions()).doesNotContainKey(id.nodeId());
        } finally {
            rx.stop();
        }
    }

    @Test
    void receiverRejectsFirstFrameThatIsNotRegistration() throws Exception {
        int port = pickFreePort();
        RecordingListener rxListener = new RecordingListener();
        SapientReceiver rx = SapientReceiver.builder("fusion-strict", port)
                .listener(rxListener)
                .enforceHandshake(true)
                .build();
        rx.start();
        try {
            // Talk to it with a plain dumb SapientTransmitter and send a StatusReport
            // FIRST — the receiver must respond with an Error and close.
            List<SapientMessage> gotBack = new CopyOnWriteArrayList<>();
            CountDownLatch replyLatch = new CountDownLatch(1);
            CountDownLatch disconnectLatch = new CountDownLatch(1);
            SapientMessageListener txListener = new SapientMessageListener() {
                @Override
                public void onMessage(SocketAddress peer, SapientMessage msg) {
                    gotBack.add(msg);
                    replyLatch.countDown();
                }
                @Override
                public void onDisconnected(SocketAddress peer) { disconnectLatch.countDown(); }
            };
            SapientTransmitter tx = new SapientTransmitter("rogue", "127.0.0.1", port, txListener);
            tx.connect();
            try {
                SapientMessage bad = MessageFactory.statusReport(UUID.randomUUID().toString(),
                        StatusReport.System.SYSTEM_OK, "default",
                        null, null, null, null, null, null);
                tx.send(bad);
                assertThat(replyLatch.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(gotBack).hasSize(1);
                assertThat(gotBack.get(0).getContentCase())
                        .isEqualTo(SapientMessage.ContentCase.ERROR);
                assertThat(disconnectLatch.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                tx.close();
            }
        } finally {
            rx.stop();
        }
    }

    @Test
    void receiverRejectsRegistrationWhenPolicyRejects() throws Exception {
        int port = pickFreePort();
        RecordingListener rxListener = new RecordingListener();
        SapientReceiver rx = SapientReceiver.builder("fusion-picky", port)
                .listener(rxListener)
                .enforceHandshake(true)
                .registrationPolicy(reg -> SapientReceiver.RegistrationOutcome.reject("nope"))
                .build();
        rx.start();
        try {
            NodeIdentity id = NodeIdentity.newRandom(Registration.NodeType.NODE_TYPE_RADAR,
                    Duration.ofSeconds(60));
            SapientEdgeClient client = SapientEdgeClient.builder("127.0.0.1", port, id)
                    .name("edge-nope")
                    .reconnectInterval(Duration.ofHours(1))
                    .build();
            try {
                client.start();
                assertThat(client.state()).isEqualTo(HandshakeState.REJECTED);
            } finally {
                client.close();
            }
        } finally {
            rx.stop();
        }
    }

    @Test
    void staleSweepClosesSilentPeer() throws Exception {
        int port = pickFreePort();
        RecordingListener rxListener = new RecordingListener();
        SapientReceiver rx = SapientReceiver.builder("fusion-sweep", port)
                .listener(rxListener)
                .enforceHandshake(true)
                .build();
        rx.start();
        try {
            // Register with a very short interval; then stall — no heartbeats.
            // Send Registration manually via a plain transmitter so we can freeze.
            List<SapientMessage> gotBack = new CopyOnWriteArrayList<>();
            CountDownLatch ackLatch = new CountDownLatch(1);
            CountDownLatch disconnectLatch = new CountDownLatch(1);
            SapientMessageListener txListener = new SapientMessageListener() {
                @Override
                public void onMessage(SocketAddress peer, SapientMessage msg) {
                    gotBack.add(msg);
                    if (msg.getContentCase() == SapientMessage.ContentCase.REGISTRATION_ACK) {
                        ackLatch.countDown();
                    }
                }
                @Override
                public void onDisconnected(SocketAddress peer) { disconnectLatch.countDown(); }
            };
            SapientTransmitter tx = new SapientTransmitter("stall", "127.0.0.1", port, txListener);
            tx.connect();
            try {
                String nodeId = UUID.randomUUID().toString();
                // 200 ms status interval → sweep should close us after ~600 ms.
                SapientMessage reg = registrationWithInterval(nodeId, 200);
                tx.send(reg);
                assertThat(ackLatch.await(2, TimeUnit.SECONDS)).isTrue();
                RegistrationAck ack = gotBack.get(0).getRegistrationAck();
                assertThat(ack.getAcceptance()).isTrue();

                // Now stay silent — sweep runs every 1s and 3× interval = 600 ms.
                // First sweep at t≈1s will find us stale.
                assertThat(disconnectLatch.await(3, TimeUnit.SECONDS))
                        .as("receiver must close a silent peer within 3× status_interval")
                        .isTrue();
                assertThat(rx.sessions()).doesNotContainKey(nodeId);
            } finally {
                tx.close();
            }
        } finally {
            rx.stop();
        }
    }

    @Test
    void dumbModeReceiverAcceptsAnyOrder() throws Exception {
        int port = pickFreePort();
        RecordingListener rxListener = new RecordingListener();
        // Legacy constructor — no handshake enforcement.
        SapientReceiver rx = new SapientReceiver("fusion-dumb", port, rxListener);
        rx.start();
        try {
            SapientMessageListener txListener = new SapientMessageListener() {
                @Override public void onMessage(SocketAddress p, SapientMessage m) { }
            };
            SapientTransmitter tx = new SapientTransmitter("any", "127.0.0.1", port, txListener);
            tx.connect();
            try {
                // Send StatusReport FIRST — dumb mode should accept it happily.
                SapientMessage sr = MessageFactory.statusReport(UUID.randomUUID().toString(),
                        StatusReport.System.SYSTEM_OK, "default",
                        null, null, null, null, null, null);
                tx.send(sr);
                assertThat(rxListener.statusLatch.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(rxListener.received).hasSize(1);
                // Registry stays empty in dumb mode.
                assertThat(rx.sessions()).isEmpty();
                assertThat(rx.enforceHandshake()).isFalse();
            } finally {
                tx.close();
            }
        } finally {
            rx.stop();
        }
    }

    /** Convenience: build a Registration with a specific status_interval in millis. */
    private static SapientMessage registrationWithInterval(String nodeId, long intervalMs) {
        Registration reg = Registration.newBuilder()
                .setIcdVersion("BSI Flex 335 v2.0")
                .setName("test")
                .addNodeDefinition(Registration.NodeDefinition.newBuilder()
                        .setNodeType(Registration.NodeType.NODE_TYPE_RADAR))
                .addCapabilities(Registration.Capability.newBuilder()
                        .setCategory("Sensor").setType("test").setValue("1").setUnits("count"))
                .setStatusDefinition(Registration.StatusDefinition.newBuilder()
                        .setStatusInterval(Registration.Duration.newBuilder()
                                .setUnits(Registration.TimeUnits.TIME_UNITS_MILLISECONDS)
                                .setValue(intervalMs)))
                .addModeDefinition(Registration.ModeDefinition.newBuilder()
                        .setModeName("default")
                        .setModeType(Registration.ModeType.MODE_TYPE_PERMANENT))
                .addConfigData(Registration.ConfigurationData.newBuilder()
                        .setManufacturer("test").setModel("test"))
                .build();
        return SapientMessage.newBuilder()
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(System.currentTimeMillis() / 1000))
                .setNodeId(nodeId)
                .setRegistration(reg)
                .build();
    }
}
