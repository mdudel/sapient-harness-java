/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.protocol;

import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identity + timing parameters for a SAPIENT node as declared in
 * its Registration message.
 *
 * <p>Used by both the edge-client state machine (to know what to declare on
 * connect) and the fusion-server state machine (to remember what a peer
 * declared so it can enforce the 3× heartbeat rule from §6.3.2.1).
 *
 * <p>The ICD version is fixed to {@value #ICD_VERSION} per this project's
 * baseline; the field remains configurable for forward-compat.
 *
 * @param nodeId         RFC-4122 UUID identifying the node (§4.2, §6.2).
 * @param nodeType       Coarse node classification from {@link Registration.NodeType}.
 * @param icdVersion     ICD version string (mandatory in Registration).
 * @param statusInterval How often the node emits StatusReport heartbeats.
 *                       The peer will consider the node dead after 3× this
 *                       (§6.3.2.1).
 */
public record NodeIdentity(
        String nodeId,
        Registration.NodeType nodeType,
        String icdVersion,
        Duration statusInterval) {

    /** Default ICD version — pinned to the spec baseline. */
    public static final String ICD_VERSION = "BSI Flex 335 v2.0";

    /** Default heartbeat interval when nothing is specified. */
    public static final Duration DEFAULT_STATUS_INTERVAL = Duration.ofSeconds(5);

    public NodeIdentity {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
        Objects.requireNonNull(icdVersion, "icdVersion");
        Objects.requireNonNull(statusInterval, "statusInterval");
        // Fail fast: UUID and enum sanity.
        try {
            UUID.fromString(nodeId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("nodeId must be a valid RFC-4122 UUID (got '"
                    + nodeId + "')", e);
        }
        if (nodeType == Registration.NodeType.NODE_TYPE_UNSPECIFIED) {
            throw new IllegalArgumentException("nodeType must not be NODE_TYPE_UNSPECIFIED (§4.2 Note 3)");
        }
        if (statusInterval.isNegative() || statusInterval.isZero()) {
            throw new IllegalArgumentException("statusInterval must be positive (got " + statusInterval + ")");
        }
    }

    /** Convenience factory using a freshly-minted UUID and default ICD + interval. */
    public static NodeIdentity newRandom(Registration.NodeType type) {
        return new NodeIdentity(
                UUID.randomUUID().toString(),
                type,
                ICD_VERSION,
                DEFAULT_STATUS_INTERVAL);
    }

    /** Convenience factory with an explicit interval. */
    public static NodeIdentity newRandom(Registration.NodeType type, Duration statusInterval) {
        return new NodeIdentity(
                UUID.randomUUID().toString(),
                type,
                ICD_VERSION,
                statusInterval);
    }

    /** The deadline (3× statusInterval per §6.3.2.1) after which a peer is considered stale. */
    public Duration staleAfter() {
        return statusInterval.multipliedBy(3);
    }
}
