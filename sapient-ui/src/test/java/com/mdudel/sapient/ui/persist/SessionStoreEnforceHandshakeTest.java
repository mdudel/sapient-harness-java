/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.persist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Back-compat + round-trip coverage for the {@code enforceHandshake}
 * fields added in Phase 4. Ensures that:
 * <ul>
 *   <li>legacy session.json files (no {@code enforceHandshake} field) still
 *       deserialize into POJOs with {@code enforceHandshake=false} \u2014 users
 *       upgrading from v0.1 don't accidentally get strict mode turned on;</li>
 *   <li>a fresh session with the flag turned on round-trips through
 *       Jackson byte-perfectly.</li>
 * </ul>
 */
class SessionStoreEnforceHandshakeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void legacyReceiverJsonDeserializesWithEnforceFalse() throws Exception {
        // Simulate a v0.1-era session.json entry with only name + port.
        String legacy = "{\"name\":\"rx-A\",\"port\":12000}";
        SessionStore.SavedReceiver r = JSON.readValue(legacy, SessionStore.SavedReceiver.class);
        assertThat(r.name).isEqualTo("rx-A");
        assertThat(r.port).isEqualTo(12000);
        assertThat(r.enforceHandshake).isFalse();
        assertThat(r.selfNodeId).isNull();
    }

    @Test
    void legacyTransmitterJsonDeserializesWithEnforceFalse() throws Exception {
        String legacy = "{\"name\":\"tx-A\",\"host\":\"127.0.0.1\",\"port\":14000,"
                + "\"nodeId\":\"11111111-1111-1111-1111-111111111111\"}";
        SessionStore.SavedTransmitter t = JSON.readValue(legacy, SessionStore.SavedTransmitter.class);
        assertThat(t.name).isEqualTo("tx-A");
        assertThat(t.host).isEqualTo("127.0.0.1");
        assertThat(t.port).isEqualTo(14000);
        assertThat(t.nodeId).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(t.enforceHandshake).isFalse();
    }

    @Test
    void modernReceiverRoundTrips() throws Exception {
        SessionStore.SavedReceiver in = new SessionStore.SavedReceiver(
                "rx-strict", 12001, true, "22222222-2222-2222-2222-222222222222");
        String json = JSON.writeValueAsString(in);
        SessionStore.SavedReceiver out = JSON.readValue(json, SessionStore.SavedReceiver.class);
        assertThat(out.name).isEqualTo(in.name);
        assertThat(out.port).isEqualTo(in.port);
        assertThat(out.enforceHandshake).isTrue();
        assertThat(out.selfNodeId).isEqualTo(in.selfNodeId);
    }

    @Test
    void modernTransmitterRoundTrips() throws Exception {
        SessionStore.SavedTransmitter in = new SessionStore.SavedTransmitter(
                "tx-strict", "127.0.0.1", 14001, "33333333-3333-3333-3333-333333333333", true);
        String json = JSON.writeValueAsString(in);
        SessionStore.SavedTransmitter out = JSON.readValue(json, SessionStore.SavedTransmitter.class);
        assertThat(out.name).isEqualTo(in.name);
        assertThat(out.host).isEqualTo(in.host);
        assertThat(out.port).isEqualTo(in.port);
        assertThat(out.nodeId).isEqualTo(in.nodeId);
        assertThat(out.enforceHandshake).isTrue();
    }

    @Test
    void twoArgReceiverConstructorDefaultsEnforceFalse() {
        SessionStore.SavedReceiver r = new SessionStore.SavedReceiver("rx", 12000);
        assertThat(r.enforceHandshake).isFalse();
        assertThat(r.selfNodeId).isNull();
    }

    @Test
    void fourArgTransmitterConstructorDefaultsEnforceFalse() {
        SessionStore.SavedTransmitter t = new SessionStore.SavedTransmitter(
                "tx", "h", 14000, "44444444-4444-4444-4444-444444444444");
        assertThat(t.enforceHandshake).isFalse();
    }
}
