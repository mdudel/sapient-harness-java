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
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@link RegistrationPolicySpec} — the JSON-serialisable
 * policy configuration that Phase 5 exposes to the UI.
 *
 * <p>Two axes: pure spec-level {@code evaluate(...)} unit tests against
 * hand-built Registration protos (fast, no sockets), and end-to-end
 * loopback tests that spin a real enforcing {@link SapientReceiver} and
 * a real {@link SapientEdgeClient} to prove the policy actually gates
 * peers on the wire.
 */
class RegistrationPolicySpecTest {

    private static int pickFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static SapientMessage makeReg(String nodeId, String icd, Registration.NodeType... types) {
        Registration.Builder reg = Registration.newBuilder()
                .setIcdVersion(icd)
                .setName("test")
                .addCapabilities(Registration.Capability.newBuilder()
                        .setCategory("Sensor").setType("test").setValue("1").setUnits("count"))
                .setStatusDefinition(Registration.StatusDefinition.newBuilder()
                        .setStatusInterval(Registration.Duration.newBuilder()
                                .setUnits(Registration.TimeUnits.TIME_UNITS_SECONDS)
                                .setValue(5f)))
                .addModeDefinition(Registration.ModeDefinition.newBuilder()
                        .setModeName("default")
                        .setModeType(Registration.ModeType.MODE_TYPE_PERMANENT))
                .addConfigData(Registration.ConfigurationData.newBuilder()
                        .setManufacturer("test").setModel("test"));
        for (Registration.NodeType t : types) {
            reg.addNodeDefinition(Registration.NodeDefinition.newBuilder().setNodeType(t));
        }
        return SapientMessage.newBuilder()
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(System.currentTimeMillis() / 1000))
                .setNodeId(nodeId)
                .setRegistration(reg.build())
                .build();
    }

    // ── Spec-level unit tests ──────────────────────────────────────────

    @Test
    void emptySpecAcceptsEverything() {
        RegistrationPolicySpec s = RegistrationPolicySpec.acceptAll();
        assertThat(s.isPermissive()).isTrue();
        SapientMessage msg = makeReg("11111111-1111-1111-1111-111111111111",
                "BSI Flex 335 v2.0", Registration.NodeType.NODE_TYPE_RADAR);
        assertThat(s.toPolicy().evaluate(msg.getRegistration()).accepted).isTrue();
    }

    @Test
    void icdVersionMismatchRejects() {
        RegistrationPolicySpec s = new RegistrationPolicySpec();
        s.requiredIcdVersion = "BSI Flex 335 v2.0";
        SapientReceiver.RegistrationOutcome o = s.toPolicy().evaluate(
                makeReg("uuid", "BSI Flex 335 v1.0", Registration.NodeType.NODE_TYPE_RADAR)
                        .getRegistration());
        assertThat(o.accepted).isFalse();
        assertThat(o.reason).contains("v1.0").contains("require");
    }

    @Test
    void icdVersionMatchAccepts() {
        RegistrationPolicySpec s = new RegistrationPolicySpec();
        s.requiredIcdVersion = "BSI Flex 335 v2.0";
        assertThat(s.toPolicy().evaluate(
                makeReg("u", "BSI Flex 335 v2.0", Registration.NodeType.NODE_TYPE_RADAR)
                        .getRegistration()).accepted).isTrue();
    }

    @Test
    void nodeTypeAllowlistRequiresIntersection() {
        RegistrationPolicySpec s = new RegistrationPolicySpec();
        s.allowedNodeTypes = List.of("NODE_TYPE_RADAR", "NODE_TYPE_LIDAR");
        assertThat(s.toPolicy().evaluate(
                makeReg("u", "any", Registration.NodeType.NODE_TYPE_CAMERA)
                        .getRegistration()).accepted).isFalse();
        assertThat(s.toPolicy().evaluate(
                makeReg("u", "any", Registration.NodeType.NODE_TYPE_LIDAR)
                        .getRegistration()).accepted).isTrue();
    }

    @Test
    void emptyNodeTypeListMeansNoConstraint() {
        RegistrationPolicySpec s = new RegistrationPolicySpec();
        s.allowedNodeTypes = List.of();
        assertThat(s.toPolicy().evaluate(
                makeReg("u", "any", Registration.NodeType.NODE_TYPE_CAMERA)
                        .getRegistration()).accepted).isTrue();
    }

    @Test
    void nodeIdAllowlistBlocksUnknownIds() {
        RegistrationPolicySpec s = new RegistrationPolicySpec();
        s.allowedNodeIds = Set.of("00000000-0000-0000-0000-000000000001");
        SapientReceiver.RegistrationOutcome blocked = s.toPolicy(
                "00000000-0000-0000-0000-000000000002")
                .evaluate(makeReg("00000000-0000-0000-0000-000000000002", "any",
                        Registration.NodeType.NODE_TYPE_RADAR).getRegistration());
        assertThat(blocked.accepted).isFalse();
        assertThat(blocked.reason).contains("allowlist");
    }

    @Test
    void nodeIdAllowlistAcceptsKnownIds() {
        RegistrationPolicySpec s = new RegistrationPolicySpec();
        s.allowedNodeIds = Set.of("00000000-0000-0000-0000-000000000001");
        assertThat(s.toPolicy("00000000-0000-0000-0000-000000000001")
                .evaluate(makeReg("00000000-0000-0000-0000-000000000001", "any",
                        Registration.NodeType.NODE_TYPE_RADAR).getRegistration())
                .accepted).isTrue();
    }

    @Test
    void nodeIdDenylistOverridesAllowlist() {
        // Fail-closed on ambiguity: if a nodeId is in BOTH lists, deny wins.
        RegistrationPolicySpec s = new RegistrationPolicySpec();
        String id = "00000000-0000-0000-0000-000000000001";
        s.allowedNodeIds = Set.of(id);
        s.deniedNodeIds  = Set.of(id);
        SapientReceiver.RegistrationOutcome o = s.toPolicy(id).evaluate(
                makeReg(id, "any", Registration.NodeType.NODE_TYPE_RADAR).getRegistration());
        assertThat(o.accepted).isFalse();
        assertThat(o.reason).contains("denylist");
    }

    @Test
    void combinedIcdAndTypeAndAllowlistAllMustPass() {
        RegistrationPolicySpec s = new RegistrationPolicySpec();
        s.requiredIcdVersion = "BSI Flex 335 v2.0";
        s.allowedNodeTypes   = List.of("NODE_TYPE_RADAR");
        s.allowedNodeIds     = Set.of("uuid-A");
        // Wrong ICD, right type, right id -> reject on ICD.
        assertThat(s.toPolicy("uuid-A").evaluate(
                makeReg("uuid-A", "BSI Flex 335 v1.0", Registration.NodeType.NODE_TYPE_RADAR)
                        .getRegistration()).accepted).isFalse();
        // Right ICD, wrong type, right id -> reject on type.
        assertThat(s.toPolicy("uuid-A").evaluate(
                makeReg("uuid-A", "BSI Flex 335 v2.0", Registration.NodeType.NODE_TYPE_LIDAR)
                        .getRegistration()).accepted).isFalse();
        // Right ICD, right type, wrong id -> reject on id.
        assertThat(s.toPolicy("uuid-B").evaluate(
                makeReg("uuid-B", "BSI Flex 335 v2.0", Registration.NodeType.NODE_TYPE_RADAR)
                        .getRegistration()).accepted).isFalse();
        // All three right -> accept.
        assertThat(s.toPolicy("uuid-A").evaluate(
                makeReg("uuid-A", "BSI Flex 335 v2.0", Registration.NodeType.NODE_TYPE_RADAR)
                        .getRegistration()).accepted).isTrue();
    }

    // ── End-to-end: spec compiled into a live receiver ─────────────────

    @Test
    void receiverAppliesSpecAndRejectsWrongIcd() throws Exception {
        int port = pickFreePort();
        RegistrationPolicySpec spec = new RegistrationPolicySpec();
        spec.requiredIcdVersion = "BSI Flex 335 v99.0"; // guarantee mismatch
        List<SapientMessage> received = new CopyOnWriteArrayList<>();
        SapientReceiver rx = SapientReceiver.builder("fusion-policy", port)
                .listener(new SapientMessageListener() {
                    @Override public void onMessage(java.net.SocketAddress p, SapientMessage m) {
                        received.add(m);
                    }
                })
                .enforceHandshake(true)
                .registrationPolicySpec(spec)
                .build();
        rx.start();
        try {
            NodeIdentity id = NodeIdentity.newRandom(Registration.NodeType.NODE_TYPE_RADAR,
                    Duration.ofSeconds(60));
            SapientEdgeClient client = SapientEdgeClient.builder("127.0.0.1", port, id)
                    .name("edge-wrong-icd")
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
    void receiverAppliesSpecAndRejectsDeniedNodeId() throws Exception {
        int port = pickFreePort();
        NodeIdentity id = NodeIdentity.newRandom(Registration.NodeType.NODE_TYPE_RADAR,
                Duration.ofSeconds(60));
        RegistrationPolicySpec spec = new RegistrationPolicySpec();
        spec.deniedNodeIds = Set.of(id.nodeId());
        SapientReceiver rx = SapientReceiver.builder("fusion-deny", port)
                .listener(new SapientMessageListener() {
                    @Override public void onMessage(java.net.SocketAddress p, SapientMessage m) { }
                })
                .enforceHandshake(true)
                .registrationPolicySpec(spec)
                .build();
        rx.start();
        try {
            SapientEdgeClient client = SapientEdgeClient.builder("127.0.0.1", port, id)
                    .name("edge-denied")
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
    void receiverAppliesSpecAndAcceptsMatchingPeer() throws Exception {
        int port = pickFreePort();
        NodeIdentity id = NodeIdentity.newRandom(Registration.NodeType.NODE_TYPE_RADAR,
                Duration.ofSeconds(60));
        RegistrationPolicySpec spec = new RegistrationPolicySpec();
        spec.requiredIcdVersion = "BSI Flex 335 v2.0";
        spec.allowedNodeTypes   = List.of("NODE_TYPE_RADAR");
        spec.allowedNodeIds     = Set.of(id.nodeId());
        SapientReceiver rx = SapientReceiver.builder("fusion-precise", port)
                .listener(new SapientMessageListener() {
                    @Override public void onMessage(java.net.SocketAddress p, SapientMessage m) { }
                })
                .enforceHandshake(true)
                .registrationPolicySpec(spec)
                .build();
        rx.start();
        try {
            SapientEdgeClient client = SapientEdgeClient.builder("127.0.0.1", port, id)
                    .name("edge-precise")
                    .build();
            try {
                client.start();
                assertThat(client.state()).isEqualTo(HandshakeState.REGISTERED);
            } finally {
                client.close();
            }
        } finally {
            // Give GOODBYE + close a moment to drain before we stop the server.
            Thread.sleep(200);
            rx.stop();
        }
    }
}
