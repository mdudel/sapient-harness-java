/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A JSON-serialisable specification of a fusion-node registration policy.
 *
 * <p>The runtime {@link SapientReceiver.RegistrationPolicy} interface is a
 * functional interface — great for composition but useless for persistence
 * or UI editing. This POJO carries the operator-configurable settings; call
 * {@link #toPolicy()} to compile it into the runtime policy the receiver
 * will actually enforce.
 *
 * <p>Design: fields are all mutable / public + no-arg constructor so
 * Jackson can round-trip it into {@code session.json} without extra
 * annotations. Missing (null) fields mean "no constraint on this axis".
 * The compiled policy is the AND of every non-null constraint — a
 * Registration must satisfy every configured field to be accepted.
 *
 * <p>Semantics of each field:
 * <ul>
 *   <li>{@link #requiredIcdVersion} — if non-null and non-blank, the
 *       {@code Registration.icd_version} must equal this string.</li>
 *   <li>{@link #allowedNodeTypes} — if non-null and non-empty, at least
 *       one of the {@code Registration.node_definition[].node_type}
 *       values must be a member. An empty list ({@code []}) is treated
 *       the same as "no constraint" — the operator likely just cleared
 *       the checkboxes without meaning "reject everything".</li>
 *   <li>{@link #allowedNodeIds} — if non-null and non-empty, the
 *       peer's envelope {@code node_id} must appear in this set. Set
 *       here because the receiver looks up the envelope UUID against
 *       an allowlist BEFORE consulting the Registration content.</li>
 *   <li>{@link #deniedNodeIds} — if non-null and non-empty, the peer's
 *       envelope {@code node_id} must NOT appear in this set. Checked
 *       BEFORE the allowlist — an entry in both lists is denied (fail
 *       closed on ambiguity).</li>
 * </ul>
 *
 * <p>{@link #toPolicy(String)} needs the envelope node_id to evaluate
 * the id lists; the {@link SapientReceiver.HandshakeEnforcingHandler}
 * always has it. The 0-arg {@link #toPolicy()} builds a Registration-
 * content-only policy for uses where node_id isn't checked (unusual).
 */
public final class RegistrationPolicySpec {

    /** Optional required ICD version string, e.g. {@code "BSI Flex 335 v2.0"}. */
    public String requiredIcdVersion;

    /**
     * Optional allowlist of node types. If non-null and non-empty, at least
     * one of the {@code Registration.node_definition[].node_type} entries
     * must be a member. Names match {@link Registration.NodeType} enum
     * constants (e.g. {@code "NODE_TYPE_RADAR"}).
     */
    public List<String> allowedNodeTypes;

    /** Optional allowlist of peer node UUIDs. See class-level docs. */
    public Set<String> allowedNodeIds;

    /** Optional denylist of peer node UUIDs. See class-level docs. */
    public Set<String> deniedNodeIds;

    public RegistrationPolicySpec() { }

    /** Convenience factory: no constraints — accept every registration. */
    public static RegistrationPolicySpec acceptAll() {
        return new RegistrationPolicySpec();
    }

    /** Convenience factory: require ICD version + optional node-type allowlist. */
    public static RegistrationPolicySpec strict(String icdVersion, String... nodeTypes) {
        RegistrationPolicySpec s = new RegistrationPolicySpec();
        s.requiredIcdVersion = icdVersion;
        if (nodeTypes != null && nodeTypes.length > 0) {
            s.allowedNodeTypes = new ArrayList<>(List.of(nodeTypes));
        }
        return s;
    }

    /** True if every constraint is empty — this spec accepts anything. */
    public boolean isPermissive() {
        return (requiredIcdVersion == null || requiredIcdVersion.isBlank())
                && (allowedNodeTypes == null || allowedNodeTypes.isEmpty())
                && (allowedNodeIds == null || allowedNodeIds.isEmpty())
                && (deniedNodeIds == null || deniedNodeIds.isEmpty());
    }

    /**
     * Compile a Registration-content-only policy — ignores the envelope
     * node_id (allow/deny lists silently pass). Useful for tests and for
     * callers that don't wire the receiver's per-connection node_id.
     */
    public SapientReceiver.RegistrationPolicy toPolicy() {
        return reg -> evaluateContent(reg);
    }

    /**
     * Compile a full policy that also enforces the allow/deny node_id lists
     * against the supplied envelope UUID.
     */
    public SapientReceiver.RegistrationPolicy toPolicy(String envelopeNodeId) {
        return reg -> {
            SapientReceiver.RegistrationOutcome idCheck = evaluateNodeId(envelopeNodeId);
            if (!idCheck.accepted) return idCheck;
            return evaluateContent(reg);
        };
    }

    private SapientReceiver.RegistrationOutcome evaluateNodeId(String envelopeNodeId) {
        if (envelopeNodeId == null || envelopeNodeId.isBlank()) {
            return SapientReceiver.RegistrationOutcome.accept();
        }
        // Deny before allow — fail closed on ambiguity.
        if (deniedNodeIds != null && !deniedNodeIds.isEmpty()
                && deniedNodeIds.contains(envelopeNodeId)) {
            return SapientReceiver.RegistrationOutcome.reject(
                    "node_id " + envelopeNodeId + " is on the denylist");
        }
        if (allowedNodeIds != null && !allowedNodeIds.isEmpty()
                && !allowedNodeIds.contains(envelopeNodeId)) {
            return SapientReceiver.RegistrationOutcome.reject(
                    "node_id " + envelopeNodeId + " is not on the allowlist");
        }
        return SapientReceiver.RegistrationOutcome.accept();
    }

    private SapientReceiver.RegistrationOutcome evaluateContent(Registration reg) {
        if (requiredIcdVersion != null && !requiredIcdVersion.isBlank()) {
            String v = reg.getIcdVersion();
            if (!requiredIcdVersion.equals(v)) {
                return SapientReceiver.RegistrationOutcome.reject(
                        "Unsupported ICD version '" + v + "' (require '"
                                + requiredIcdVersion + "')");
            }
        }
        if (allowedNodeTypes != null && !allowedNodeTypes.isEmpty()) {
            Set<String> declared = new LinkedHashSet<>();
            for (Registration.NodeDefinition nd : reg.getNodeDefinitionList()) {
                declared.add(nd.getNodeType().name());
            }
            boolean any = false;
            for (String t : allowedNodeTypes) {
                if (declared.contains(t)) { any = true; break; }
            }
            if (!any) {
                return SapientReceiver.RegistrationOutcome.reject(
                        "Declared node types " + declared
                                + " do not intersect allowlist "
                                + new TreeSet<>(allowedNodeTypes));
            }
        }
        return SapientReceiver.RegistrationOutcome.accept();
    }
}
