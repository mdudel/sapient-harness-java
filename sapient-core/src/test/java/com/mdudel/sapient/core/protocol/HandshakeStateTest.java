/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HandshakeStateTest {

    // §6.2.2: only Registration (or Error) may cross the wire before REGISTERED.

    @Test
    void newStateOnlyAllowsRegistrationAndError() {
        HandshakeState s = HandshakeState.NEW;
        assertThat(s.canSend("REGISTRATION")).isTrue();
        assertThat(s.canSend("ERROR")).isTrue();
        assertThat(s.canSend("STATUS_REPORT")).isFalse();
        assertThat(s.canSend("DETECTION_REPORT")).isFalse();
        assertThat(s.canSend("TASK")).isFalse();
        assertThat(s.canSend("REGISTRATION_ACK")).isFalse();
    }

    @Test
    void registeringAllowsAckAndErrorOnly() {
        HandshakeState s = HandshakeState.REGISTERING;
        assertThat(s.canSend("REGISTRATION_ACK")).isTrue();
        assertThat(s.canSend("ERROR")).isTrue();
        assertThat(s.canSend("REGISTRATION")).isFalse();
        assertThat(s.canSend("STATUS_REPORT")).isFalse();
    }

    @Test
    void registeredAllowsEverythingExceptSecondRegistration() {
        HandshakeState s = HandshakeState.REGISTERED;
        assertThat(s.canSend("STATUS_REPORT")).isTrue();
        assertThat(s.canSend("DETECTION_REPORT")).isTrue();
        assertThat(s.canSend("TASK")).isTrue();
        assertThat(s.canSend("TASK_ACK")).isTrue();
        assertThat(s.canSend("ALERT")).isTrue();
        assertThat(s.canSend("ALERT_ACK")).isTrue();
        assertThat(s.canSend("ERROR")).isTrue();
        assertThat(s.canSend("REGISTRATION")).isFalse();
    }

    @Test
    void goodbyeAndTerminalStatesRejectEverything() {
        for (HandshakeState s : new HandshakeState[]{
                HandshakeState.GOODBYE,
                HandshakeState.REJECTED,
                HandshakeState.CLOSED }) {
            assertThat(s.canSend("STATUS_REPORT")).as("state=%s", s).isFalse();
            assertThat(s.canSend("REGISTRATION")).as("state=%s", s).isFalse();
            assertThat(s.canSend("ERROR")).as("state=%s", s).isFalse();
        }
    }

    @Test
    void nullOrGarbageContentIsRejected() {
        assertThat(HandshakeState.NEW.canSend(null)).isFalse();
        assertThat(HandshakeState.REGISTERED.canSend(null)).isFalse();
        assertThat(HandshakeState.NEW.canSend("NOT_A_REAL_TYPE")).isFalse();
    }

    @Test
    void canSendIsCaseAndUnderscoreInsensitive() {
        // Callers may pass "REGISTRATION", "Registration", or the raw
        // ContentCase.name() which uses SCREAMING_SNAKE_CASE. All three work.
        assertThat(HandshakeState.NEW.canSend("REGISTRATION")).isTrue();
        assertThat(HandshakeState.NEW.canSend("Registration")).isTrue();
        assertThat(HandshakeState.NEW.canSend("registration")).isTrue();
        assertThat(HandshakeState.REGISTERED.canSend("STATUS_REPORT")).isTrue();
        assertThat(HandshakeState.REGISTERED.canSend("statusreport")).isTrue();
        assertThat(HandshakeState.REGISTERED.canSend("status_report")).isTrue();
    }

    @Test
    void terminalStatesAreFlagged() {
        assertThat(HandshakeState.NEW.isTerminal()).isFalse();
        assertThat(HandshakeState.REGISTERING.isTerminal()).isFalse();
        assertThat(HandshakeState.REGISTERED.isTerminal()).isFalse();
        assertThat(HandshakeState.GOODBYE.isTerminal()).isFalse();
        assertThat(HandshakeState.REJECTED.isTerminal()).isTrue();
        assertThat(HandshakeState.CLOSED.isTerminal()).isTrue();
    }
}
