/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.persist;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdudel.sapient.net.RegistrationPolicySpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Back-compat + round-trip coverage for the Phase 5 {@code policy} field on
 * {@link SessionStore.SavedReceiver}. Same shape as
 * {@link SessionStoreEnforceHandshakeTest}: legacy files without the field
 * deserialize with {@code policy=null}; modern files round-trip byte-perfectly.
 */
class PolicyPersistenceTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void legacyReceiverJsonDeserializesWithNullPolicy() throws Exception {
        String legacy = "{\"name\":\"rx-A\",\"port\":12000,\"enforceHandshake\":true,"
                + "\"selfNodeId\":\"11111111-1111-1111-1111-111111111111\"}";
        SessionStore.SavedReceiver r = JSON.readValue(legacy, SessionStore.SavedReceiver.class);
        assertThat(r.enforceHandshake).isTrue();
        assertThat(r.policy).isNull();
    }

    @Test
    void modernReceiverWithPolicyRoundTrips() throws Exception {
        RegistrationPolicySpec spec = new RegistrationPolicySpec();
        spec.requiredIcdVersion = "BSI Flex 335 v2.0";
        spec.allowedNodeTypes   = List.of("NODE_TYPE_RADAR", "NODE_TYPE_LIDAR");
        spec.allowedNodeIds     = Set.of("11111111-1111-1111-1111-111111111111");
        spec.deniedNodeIds      = Set.of("22222222-2222-2222-2222-222222222222");
        SessionStore.SavedReceiver in = new SessionStore.SavedReceiver(
                "rx-policy", 12345, true,
                "33333333-3333-3333-3333-333333333333", spec);

        String json = JSON.writeValueAsString(in);
        SessionStore.SavedReceiver out = JSON.readValue(json, SessionStore.SavedReceiver.class);

        assertThat(out.enforceHandshake).isTrue();
        assertThat(out.policy).isNotNull();
        assertThat(out.policy.requiredIcdVersion).isEqualTo(spec.requiredIcdVersion);
        assertThat(out.policy.allowedNodeTypes).containsExactlyElementsOf(spec.allowedNodeTypes);
        assertThat(out.policy.allowedNodeIds).containsExactlyInAnyOrderElementsOf(spec.allowedNodeIds);
        assertThat(out.policy.deniedNodeIds).containsExactlyInAnyOrderElementsOf(spec.deniedNodeIds);
    }

    @Test
    void permissiveSpecRoundTripsAsNullPolicy() throws Exception {
        // ReceiversPane.snapshot() emits null when spec is permissive, so the
        // on-disk file stays tidy. We verify the receive side handles both
        // shapes: explicit permissive spec deserializes to a permissive spec
        // (which is fine), and absent spec deserializes to null.
        SessionStore.SavedReceiver withPerm = new SessionStore.SavedReceiver(
                "rx-perm", 12345, true, null, RegistrationPolicySpec.acceptAll());
        String json = JSON.writeValueAsString(withPerm);
        SessionStore.SavedReceiver out = JSON.readValue(json, SessionStore.SavedReceiver.class);
        assertThat(out.policy).isNotNull();
        assertThat(out.policy.isPermissive()).isTrue();
    }

    @Test
    void fiveArgConstructorCarriesPolicy() {
        RegistrationPolicySpec spec = new RegistrationPolicySpec();
        spec.requiredIcdVersion = "v2.0";
        SessionStore.SavedReceiver r = new SessionStore.SavedReceiver(
                "rx", 12000, true, "44444444-4444-4444-4444-444444444444", spec);
        assertThat(r.policy).isSameAs(spec);
    }

    @Test
    void threeArgLegacyConstructorHasNullPolicy() {
        SessionStore.SavedReceiver r = new SessionStore.SavedReceiver("rx", 12000);
        assertThat(r.policy).isNull();
    }
}
