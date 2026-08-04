/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.persist;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mdudel.sapient.net.RegistrationPolicySpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persist all UI state between runs to a single JSON file.
 *
 * <p>Stored at {@code ${user.home}/.sapient-harness/session.json}. Contains:
 * <ul>
 *   <li>configured receivers (name + port),</li>
 *   <li>configured transmitters (name + host + port + stable node_id),</li>
 *   <li>current theme name.</li>
 * </ul>
 *
 * <p>Only configuration data is persisted — runtime state (open sockets,
 * generator activity) is deliberately NOT saved because sockets don't survive
 * a JVM restart anyway. Restored receivers come up stopped; restored
 * transmitters come up disconnected.
 *
 * <p>Save is best-effort — a write failure never crashes the UI. Load falls
 * back to an empty session if the file is missing or unparseable.
 */
public final class SessionStore {

    /** File where all UI config is persisted. */
    private static final Path SESSION_FILE = Path.of(
            System.getProperty("user.home"), ".sapient-harness", "session.json");

    private static final ObjectMapper JSON = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            // Forward-compat: don't die if a future field lands in an old JAR.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private SessionStore() {
    }

    /**
     * POJO: a saved receiver (name + port, plus handshake-enforcement flag
     * and a stable fusion-side node UUID).
     *
     * <p>{@code enforceHandshake} is persisted so a receiver marked strict
     * in one session comes back up strict on the next. It defaults to false
     * in the no-arg constructor and via Jackson deserialization when the
     * field is absent, giving byte-identical behaviour to legacy sessions.
     *
     * <p>{@code selfNodeId} is persisted separately so a strict receiver
     * presents a stable fusion-node identity across restarts. Populated
     * lazily on first start when null.
     */
    public static final class SavedReceiver {
        public String name;
        public int port;
        /** BSI Flex 335 v2.0 handshake enforcement toggle (default false = legacy dumb mode). */
        public boolean enforceHandshake;
        /** Stable UUID used as this fusion node's {@code node_id} when strict. Nullable. */
        public String selfNodeId;
        /**
         * Optional registration policy spec (Phase 5). When null AND
         * {@link #enforceHandshake} is true, the receiver accepts every
         * peer (legacy Phase-3 behaviour). When populated, the receiver
         * gates registrations on ICD version / node type allowlist /
         * node_id allowlist / node_id denylist.
         */
        public RegistrationPolicySpec policy;

        public SavedReceiver() {}   // required by Jackson
        public SavedReceiver(String name, int port) {
            this(name, port, false, null, null);
        }
        public SavedReceiver(String name, int port, boolean enforceHandshake, String selfNodeId) {
            this(name, port, enforceHandshake, selfNodeId, null);
        }
        public SavedReceiver(String name, int port, boolean enforceHandshake,
                             String selfNodeId, RegistrationPolicySpec policy) {
            this.name = name;
            this.port = port;
            this.enforceHandshake = enforceHandshake;
            this.selfNodeId = selfNodeId;
            this.policy = policy;
        }
    }

    /**
     * POJO: a saved transmitter (name + host + port + stable node_id, plus
     * handshake-enforcement flag). See {@link SavedReceiver} for the
     * back-compat notes on the enforcement field.
     */
    public static final class SavedTransmitter {
        public String name;
        public String host;
        public int port;
        /** Preserved across runs so receivers see the same SAPIENT node identity. */
        public String nodeId;
        /** BSI Flex 335 v2.0 handshake enforcement toggle (default false = legacy dumb mode). */
        public boolean enforceHandshake;

        public SavedTransmitter() {}
        public SavedTransmitter(String name, String host, int port, String nodeId) {
            this(name, host, port, nodeId, false);
        }
        public SavedTransmitter(String name, String host, int port, String nodeId, boolean enforceHandshake) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.nodeId = nodeId;
            this.enforceHandshake = enforceHandshake;
        }
    }

    /** Root container for the on-disk JSON document. */
    public static final class Session {
        public List<SavedReceiver> receivers = new ArrayList<>();
        public List<SavedTransmitter> transmitters = new ArrayList<>();
        /** Current theme display name (see {@link com.mdudel.sapient.ui.ThemeManager}). */
        public String theme;
    }

    /**
     * Load the session from disk. Returns an empty {@link Session} if the file
     * doesn't exist or can't be parsed — the UI comes up with a clean slate
     * rather than failing to start.
     */
    public static Session load() {
        try {
            if (Files.exists(SESSION_FILE)) {
                Session s = JSON.readValue(SESSION_FILE.toFile(), Session.class);
                if (s != null) {
                    if (s.receivers == null) s.receivers = new ArrayList<>();
                    if (s.transmitters == null) s.transmitters = new ArrayList<>();
                    return s;
                }
            }
        } catch (IOException ignored) {
            // fall through to empty session
        }
        return new Session();
    }

    /**
     * Save the session to disk. Silent-fail on IO errors — a config-write
     * hiccup should never kill the UI or lose the current runtime state.
     */
    public static void save(Session session) {
        try {
            Files.createDirectories(SESSION_FILE.getParent());
            JSON.writeValue(SESSION_FILE.toFile(), session);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** The absolute path of the session file, for logging / help messages. */
    public static Path path() {
        return SESSION_FILE;
    }
}
