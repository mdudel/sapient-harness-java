/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.protocol;

/**
 * Lifecycle of a SAPIENT peer session as defined by BSI Flex 335 v2.0 §4.4
 * (Initialization) and §6.2 (Registration / RegistrationAck).
 *
 * <p>Both edge (client) and fusion (server) sides move through these states.
 * The edge node <em>drives</em> the sequence — no messages other than
 * {@code Registration} may be sent before a {@code RegistrationAck} arrives
 * (§6.2.2). {@link #REGISTERED} is the steady state where StatusReport
 * heartbeats, DetectionReports, and Tasks flow.
 *
 * <p>Legal transitions:
 * <pre>
 *   NEW           → REGISTERING       (edge sent Registration; server received it)
 *   REGISTERING   → REGISTERED        (RegistrationAck.acceptance = true)
 *   REGISTERING   → REJECTED          (RegistrationAck.acceptance = false)
 *   REGISTERED    → GOODBYE           (edge sending StatusReport.system = GOODBYE)
 *   GOODBYE       → CLOSED            (clean disconnect after GOODBYE flush)
 *   any           → CLOSED            (socket died, timeout, or error)
 * </pre>
 *
 * <p>The {@link #canSend(String)} guard encodes the §6.2.2 rule: nothing
 * except {@code Registration} may cross the wire before {@link #REGISTERED}.
 */
public enum HandshakeState {

    /** TCP connected (or not yet), no Registration exchanged. */
    NEW,

    /** Registration has been sent; awaiting RegistrationAck or Error. */
    REGISTERING,

    /** RegistrationAck.acceptance=true received. Steady state — traffic allowed. */
    REGISTERED,

    /** RegistrationAck.acceptance=false. Terminal for this session; socket should close. */
    REJECTED,

    /** StatusReport.system=GOODBYE sent; awaiting clean socket close (§4.8). */
    GOODBYE,

    /** Socket closed (clean, error, or timeout). Terminal. */
    CLOSED;

    /**
     * Test whether a given content type is allowed to be <em>sent</em> in the
     * current state. Enforces §6.2.2: only Registration may cross the wire
     * before {@link #REGISTERED}. RegistrationAck is legal in {@link #NEW}
     * or {@link #REGISTERING} on the fusion side (it's what moves the edge
     * peer from REGISTERING → REGISTERED).
     *
     * @param contentCase the {@code SapientMessage.ContentCase} name, e.g.
     *                    {@code "REGISTRATION"}, {@code "DETECTION_REPORT"}.
     *                    Case-insensitive; underscores optional.
     * @return true if a message with that content is legal to send now.
     */
    public boolean canSend(String contentCase) {
        if (contentCase == null) return false;
        String c = contentCase.toUpperCase().replace("_", "");
        return switch (this) {
            case NEW -> c.equals("REGISTRATION") || c.equals("ERROR");
            case REGISTERING -> c.equals("REGISTRATIONACK") || c.equals("ERROR");
            case REGISTERED -> !c.equals("REGISTRATION"); // anything except re-register
            case GOODBYE -> false; // socket closing; nothing else goes out
            case REJECTED, CLOSED -> false;
        };
    }

    /** True if the state is a terminal one — nothing else will happen on this session. */
    public boolean isTerminal() {
        return this == REJECTED || this == CLOSED;
    }
}
