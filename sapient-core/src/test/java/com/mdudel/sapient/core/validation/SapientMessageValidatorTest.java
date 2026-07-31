/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.validation;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SapientMessageValidatorTest {

    @Test
    void wellFormedRegistrationIsValid() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build())
                .setNodeId(UUID.randomUUID().toString())
                .setRegistration(Registration.newBuilder().build())
                .build();

        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).as(r.summary()).isTrue();
        assertThat(r.errors()).isEmpty();
    }

    @Test
    void missingContentOneofIsInvalid() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId(UUID.randomUUID().toString())
                .build(); // NO oneof set

        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("content oneof"));
    }

    @Test
    void badNodeIdIsInvalid() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId("not-a-uuid")
                .setRegistration(Registration.newBuilder().build())
                .build();

        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("node_id"));
    }

    @Test
    void emptyMessageAccumulatesMultipleErrors() {
        SapientMessage msg = SapientMessage.getDefaultInstance();
        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).hasSizeGreaterThanOrEqualTo(3); // timestamp + node_id + oneof
    }
}
