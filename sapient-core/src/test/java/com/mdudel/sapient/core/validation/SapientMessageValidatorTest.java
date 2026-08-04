/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.validation;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SapientMessageValidatorTest {

    // ------------------------------------------------------------------
    // Envelope-level rules (originally v0.1)
    // ------------------------------------------------------------------

    @Test
    void wellFormedRegistrationIsValid() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build())
                .setNodeId(UUID.randomUUID().toString())
                .setRegistration(minimalRegistration())
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
                .setRegistration(minimalRegistration())
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

    // ------------------------------------------------------------------
    // Registration content rules (v0.2)
    // ------------------------------------------------------------------

    @Test
    void emptyRegistrationFailsAllContentRules() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId(UUID.randomUUID().toString())
                .setRegistration(Registration.newBuilder().build()) // fully default
                .build();

        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).isFalse();
        // node_definition + icd_version + capabilities + status_definition + mode_definition + config_data
        assertThat(r.errors())
                .anyMatch(e -> e.contains("node_definition"))
                .anyMatch(e -> e.contains("icd_version"))
                .anyMatch(e -> e.contains("capabilities"))
                .anyMatch(e -> e.contains("status_definition"))
                .anyMatch(e -> e.contains("mode_definition"))
                .anyMatch(e -> e.contains("config_data"));
    }

    @Test
    void registrationWithUnspecifiedStatusIntervalUnitsIsInvalid() {
        Registration reg = minimalRegistration().toBuilder()
                .setStatusDefinition(Registration.StatusDefinition.newBuilder()
                        .setStatusInterval(Registration.Duration.newBuilder()
                                // NO units set — defaults to TIME_UNITS_UNSPECIFIED
                                .setValue(5f)
                                .build())
                        .build())
                .build();
        SapientMessage msg = envelope(reg);
        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("UNSPECIFIED"));
    }

    @Test
    void registrationWithZeroStatusIntervalIsInvalid() {
        Registration reg = minimalRegistration().toBuilder()
                .setStatusDefinition(Registration.StatusDefinition.newBuilder()
                        .setStatusInterval(Registration.Duration.newBuilder()
                                .setUnits(Registration.TimeUnits.TIME_UNITS_SECONDS)
                                .setValue(0f)
                                .build())
                        .build())
                .build();
        SapientMessage msg = envelope(reg);
        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("must be > 0"));
    }

    // ------------------------------------------------------------------
    // RegistrationAck content rules (v0.2)
    // ------------------------------------------------------------------

    @Test
    void registrationAckWithoutDestinationIdIsInvalid() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId(UUID.randomUUID().toString())
                // NO destination_id — spec Table 1 says it MUST echo the registering node's UUID
                .setRegistrationAck(RegistrationAck.newBuilder().setAcceptance(true).build())
                .build();
        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("destination_id"));
    }

    @Test
    void registrationAckAcceptTrueWithDestinationIsValid() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId(UUID.randomUUID().toString())
                .setDestinationId(UUID.randomUUID().toString())
                .setRegistrationAck(RegistrationAck.newBuilder().setAcceptance(true).build())
                .build();
        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).as(r.summary()).isTrue();
    }

    @Test
    void registrationAckRejectMustCarryReason() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId(UUID.randomUUID().toString())
                .setDestinationId(UUID.randomUUID().toString())
                .setRegistrationAck(RegistrationAck.newBuilder()
                        .setAcceptance(false)
                        // no reasons attached
                        .build())
                .build();
        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("ack_response_reason"));
    }

    @Test
    void registrationAckRejectWithReasonIsValid() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId(UUID.randomUUID().toString())
                .setDestinationId(UUID.randomUUID().toString())
                .setRegistrationAck(RegistrationAck.newBuilder()
                        .setAcceptance(false)
                        .addAckResponseReason("unknown node type")
                        .build())
                .build();
        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).as(r.summary()).isTrue();
    }

    // ------------------------------------------------------------------
    // StatusReport content rules (v0.2)
    // ------------------------------------------------------------------

    @Test
    void statusReportWithUnspecifiedSystemIsInvalid() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId(UUID.randomUUID().toString())
                .setStatusReport(StatusReport.newBuilder().build()) // system defaults to SYSTEM_UNSPECIFIED
                .build();
        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("SYSTEM_UNSPECIFIED"));
    }

    @Test
    void statusReportWithOkSystemIsValid() {
        SapientMessage msg = SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId(UUID.randomUUID().toString())
                .setStatusReport(StatusReport.newBuilder()
                        .setSystem(StatusReport.System.SYSTEM_OK)
                        .build())
                .build();
        SapientMessageValidator.ValidationResult r = SapientMessageValidator.validate(msg);
        assertThat(r.valid()).as(r.summary()).isTrue();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Fully-populated minimum-valid Registration for the mandatory-field rules. */
    private static Registration minimalRegistration() {
        return Registration.newBuilder()
                .addNodeDefinition(Registration.NodeDefinition.newBuilder()
                        .setNodeType(Registration.NodeType.NODE_TYPE_RADAR)
                        .build())
                .setIcdVersion("BSI Flex 335 v2.0")
                .addCapabilities(Registration.Capability.newBuilder()
                        .setCategory("Radar")
                        .setType("MaxRange")
                        .build())
                .setStatusDefinition(Registration.StatusDefinition.newBuilder()
                        .setStatusInterval(Registration.Duration.newBuilder()
                                .setUnits(Registration.TimeUnits.TIME_UNITS_SECONDS)
                                .setValue(5f)
                                .build())
                        .build())
                .addModeDefinition(Registration.ModeDefinition.newBuilder()
                        .setModeName("default")
                        .setModeType(Registration.ModeType.MODE_TYPE_PERMANENT)
                        .build())
                .addConfigData(Registration.ConfigurationData.newBuilder()
                        .setManufacturer("mdudel test")
                        .build())
                .build();
    }

    private static SapientMessage envelope(Registration reg) {
        return SapientMessage.newBuilder()
                .setTimestamp(Timestamp.newBuilder().build())
                .setNodeId(UUID.randomUUID().toString())
                .setRegistration(reg)
                .build();
    }
}
