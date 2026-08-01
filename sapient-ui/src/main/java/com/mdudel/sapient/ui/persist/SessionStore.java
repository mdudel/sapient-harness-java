/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.persist;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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
            .enable(SerializationFeature.INDENT_OUTPUT);

    private SessionStore() {
    }

    /** POJO: a saved receiver (name + port). */
    public static final class SavedReceiver {
        public String name;
        public int port;

        public SavedReceiver() {}   // required by Jackson
        public SavedReceiver(String name, int port) {
            this.name = name;
            this.port = port;
        }
    }

    /** POJO: a saved transmitter (name + host + port + stable node_id). */
    public static final class SavedTransmitter {
        public String name;
        public String host;
        public int port;
        /** Preserved across runs so receivers see the same SAPIENT node identity. */
        public String nodeId;

        public SavedTransmitter() {}
        public SavedTransmitter(String name, String host, int port, String nodeId) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.nodeId = nodeId;
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
