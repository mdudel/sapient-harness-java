/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.validation;

import com.google.protobuf.Descriptors;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

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
 * <p>Rules currently enforced (v0.1):
 * <ul>
 *   <li>{@code timestamp} must be present</li>
 *   <li>{@code node_id} must be present and a valid UUID</li>
 *   <li>if {@code destination_id} is set, it must be a valid UUID</li>
 *   <li>exactly one {@code content} oneof branch must be set</li>
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
        }

        return new ValidationResult(errors.isEmpty(), errors, describe(msg));
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
