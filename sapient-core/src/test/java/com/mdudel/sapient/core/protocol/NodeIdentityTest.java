/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.protocol;

import org.junit.jupiter.api.Test;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeIdentityTest {

    @Test
    void wellFormedIdentityAccepted() {
        NodeIdentity id = new NodeIdentity(
                UUID.randomUUID().toString(),
                Registration.NodeType.NODE_TYPE_RADAR,
                "BSI Flex 335 v2.0",
                Duration.ofSeconds(5));
        assertThat(id.nodeId()).isNotBlank();
        assertThat(id.icdVersion()).isEqualTo("BSI Flex 335 v2.0");
        assertThat(id.statusInterval()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void nodeIdMustBeUuid() {
        assertThatThrownBy(() -> new NodeIdentity(
                "not-a-uuid",
                Registration.NodeType.NODE_TYPE_RADAR,
                "BSI Flex 335 v2.0",
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void nodeTypeUnspecifiedIsRejected() {
        assertThatThrownBy(() -> new NodeIdentity(
                UUID.randomUUID().toString(),
                Registration.NodeType.NODE_TYPE_UNSPECIFIED,
                "BSI Flex 335 v2.0",
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSPECIFIED");
    }

    @Test
    void zeroOrNegativeIntervalIsRejected() {
        assertThatThrownBy(() -> new NodeIdentity(
                UUID.randomUUID().toString(),
                Registration.NodeType.NODE_TYPE_RADAR,
                "BSI Flex 335 v2.0",
                Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeIdentity(
                UUID.randomUUID().toString(),
                Registration.NodeType.NODE_TYPE_RADAR,
                "BSI Flex 335 v2.0",
                Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newRandomFactoriesProduceValidIdentity() {
        NodeIdentity a = NodeIdentity.newRandom(Registration.NodeType.NODE_TYPE_CAMERA);
        assertThat(UUID.fromString(a.nodeId())).isNotNull(); // parses cleanly
        assertThat(a.statusInterval()).isEqualTo(NodeIdentity.DEFAULT_STATUS_INTERVAL);
        assertThat(a.icdVersion()).isEqualTo(NodeIdentity.ICD_VERSION);

        NodeIdentity b = NodeIdentity.newRandom(
                Registration.NodeType.NODE_TYPE_LIDAR,
                Duration.ofSeconds(2));
        assertThat(b.statusInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(b.nodeId()).isNotEqualTo(a.nodeId());
    }

    @Test
    void staleAfterIsThreeTimesInterval() {
        // §6.3.2.1 — 3× interval before a peer is considered dead.
        NodeIdentity id = NodeIdentity.newRandom(
                Registration.NodeType.NODE_TYPE_RADAR,
                Duration.ofSeconds(7));
        assertThat(id.staleAfter()).isEqualTo(Duration.ofSeconds(21));
    }
}
