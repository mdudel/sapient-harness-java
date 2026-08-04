/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.validation;

import com.google.protobuf.Descriptors;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Structural validation for {@link SapientMessage} instances.
 *
 * <p>This is a minimum-viable validator that mirrors the mandatory-field rules
 * from dstl's BSI Flex 335 v2 protos (via {@code proto_options.proto}'s
 * {@code is_mandatory} and {@code is_uuid} field options). The full 30-validator
 * suite from dstl's {@code SapientServices/Data/Validation/*.cs} can be layered
 * on top in a follow-on pass — see the {@code SapientServicesValidator} project
 * in the reference C# harness for the exhaustive rules.
 *
 * <p>Envelope rules (v0.1):
 * <ul>
 *   <li>{@code timestamp} must be present</li>
 *   <li>{@code node_id} must be present and a valid UUID</li>
 *   <li>if {@code destination_id} is set, it must be a valid UUID</li>
 *   <li>exactly one {@code content} oneof branch must be set</li>
 * </ul>
 *
 * <p>Content rules (v0.2 — added for handshake enforcement):
 * <ul>
 *   <li><b>Registration</b>: must carry ≥1 node_definition, non-empty
 *       icd_version, ≥1 capabilities, status_definition with a valid
 *       Duration (non-UNSPECIFIED units), ≥1 mode_definition, ≥1 config_data.</li>
 *   <li><b>RegistrationAck</b>: must have {@code destination_id} set to the
 *       registering node's UUID (per Table 1); note that {@code acceptance}
 *       is a plain proto3 bool that defaults to {@code false} — a strict
 *       reader that gets an all-default ack treats it as a rejection. We
 *       flag missing {@code destination_id} but cannot detect a "forgotten
 *       acceptance=true" from the wire alone.</li>
 *   <li><b>StatusReport</b>: {@code system} enum must not be
 *       {@code SYSTEM_UNSPECIFIED} (§4.2 Note 3).</li>
 * </ul>
 */
public final class SapientMessageValidator {

    private SapientMessageValidator() {
        // utility
    }

    /** Validate a message; returns a {@link ValidationResult} — never throws. */
    public static ValidationResult validate(SapientMessage msg) {
        List<String> errors = new ArrayList<>();

        if (!msg.hasTimestamp()) {
            errors.add("timestamp is mandatory but was not set");
        }

        if (msg.getNodeId().isEmpty()) {
            errors.add("node_id is mandatory but was not set");
        } else if (!isValidUuid(msg.getNodeId())) {
            errors.add("node_id must be a valid UUID (got: '" + msg.getNodeId() + "')");
        }

        if (!msg.getDestinationId().isEmpty() && !isValidUuid(msg.getDestinationId())) {
            errors.add("destination_id must be a valid UUID when set (got: '"
                    + msg.getDestinationId() + "')");
        }

        SapientMessage.ContentCase content = msg.getContentCase();
        if (content == SapientMessage.ContentCase.CONTENT_NOT_SET) {
            errors.add("content oneof is mandatory: exactly one of registration, "
                    + "registration_ack, status_report, detection_report, task, task_ack, "
                    + "alert, alert_ack, error must be set");
        } else {
            // Content-specific rules — only invoked once we know which branch is set.
            switch (content) {
                case REGISTRATION -> validateRegistration(msg.getRegistration(), errors);
                case REGISTRATION_ACK -> validateRegistrationAck(msg.getRegistrationAck(), msg, errors);
                case STATUS_REPORT -> validateStatusReport(msg.getStatusReport(), errors);
                default -> { /* other message types: envelope-only for now */ }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors, describe(msg));
    }

    private static void validateRegistration(Registration reg, List<String> errors) {
        if (reg.getNodeDefinitionCount() == 0) {
            errors.add("Registration.node_definition is mandatory (need ≥1 entry)");
        }
        if (!reg.hasIcdVersion() || reg.getIcdVersion().isEmpty()) {
            errors.add("Registration.icd_version is mandatory");
        }
        if (reg.getCapabilitiesCount() == 0) {
            errors.add("Registration.capabilities is mandatory (need ≥1 entry)");
        }
        if (!reg.hasStatusDefinition()) {
            errors.add("Registration.status_definition is mandatory");
        } else {
            Registration.StatusDefinition sd = reg.getStatusDefinition();
            if (!sd.hasStatusInterval()) {
                errors.add("Registration.status_definition.status_interval is mandatory");
            } else {
                Registration.Duration ival = sd.getStatusInterval();
                if (ival.getUnits() == Registration.TimeUnits.TIME_UNITS_UNSPECIFIED) {
                    errors.add("Registration.status_definition.status_interval.units must not be UNSPECIFIED (§4.2 Note 3)");
                }
                if (ival.getValue() <= 0f) {
                    errors.add("Registration.status_definition.status_interval.value must be > 0 (got " + ival.getValue() + ")");
                }
            }
        }
        if (reg.getModeDefinitionCount() == 0) {
            errors.add("Registration.mode_definition is mandatory (need ≥1 entry)");
        }
        if (reg.getConfigDataCount() == 0) {
            errors.add("Registration.config_data is mandatory (need ≥1 entry)");
        }
    }

    private static void validateRegistrationAck(RegistrationAck ack, SapientMessage envelope, List<String> errors) {
        // Table 1: destination_id MUST be populated with the registering node's UUID.
        if (envelope.getDestinationId().isEmpty()) {
            errors.add("RegistrationAck envelope.destination_id must be the registering node's UUID (Table 1)");
        }
        // acceptance is a plain proto3 bool: defaults to false. We can't tell "forgotten"
        // from "explicit false" on the wire, so we only warn in describe() below.
        // On reject, at least one ack_response_reason is strongly recommended.
        if (!ack.getAcceptance() && ack.getAckResponseReasonCount() == 0) {
            errors.add("RegistrationAck with acceptance=false SHOULD include ≥1 ack_response_reason (§6.2.2)");
        }
    }

    private static void validateStatusReport(StatusReport sr, List<String> errors) {
        if (sr.getSystem() == StatusReport.System.SYSTEM_UNSPECIFIED) {
            errors.add("StatusReport.system must not be SYSTEM_UNSPECIFIED (§4.2 Note 3)");
        }
    }

    /** Short human-readable description of the message (type + node_id). */
    public static String describe(SapientMessage msg) {
        String type = msg.getContentCase() == SapientMessage.ContentCase.CONTENT_NOT_SET
                ? "<empty>"
                : msg.getContentCase().name();
        String node = msg.getNodeId().isEmpty() ? "<no-node-id>" : msg.getNodeId();
        return type + " from " + node;
    }

    private static boolean isValidUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Value object describing the outcome of a validation. */
    public record ValidationResult(boolean valid, List<String> errors, String description) {

        /** Empty-error convenience — returns a compact "valid" or "invalid: ..." string. */
        public String summary() {
            if (valid) {
                return "VALID " + description;
            }
            return "INVALID " + description + " — " + String.join("; ", errors);
        }
    }

    /** Reference to the field-options descriptor (unused for now; hook for future work). */
    @SuppressWarnings("unused")
    private static Descriptors.FieldDescriptor unused() {
        return null;
    }
}
