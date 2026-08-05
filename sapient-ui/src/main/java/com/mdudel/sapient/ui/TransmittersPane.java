/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.factory.MessageFactory;
import com.mdudel.sapient.core.gen.DetectionGenerator;
import com.mdudel.sapient.core.gen.SensorGenerator;
import com.mdudel.sapient.core.protocol.HandshakeState;
import com.mdudel.sapient.core.protocol.NodeIdentity;
import com.mdudel.sapient.net.SapientEdgeClient;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientTransmitter;
import com.mdudel.sapient.ui.dialog.EditDialogs;
import com.mdudel.sapient.ui.dialog.MessageDialogs;
import com.mdudel.sapient.ui.persist.SessionStore;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;

import java.net.SocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * TransmittersPane — VBox-of-cards layout (2026-08-05).
 *
 * <p>Replaces the previous TableView. Each transmitter is now a two-row
 * "card" so a per-transmitter Message picker + Send + Zap sit on Row 1
 * next to their target row (users kept confusing which transmitter the
 * old shared picker was targeting), and the runtime read-out (Status /
 * Sent / Activity) drops to Row 2 so nothing needs to squeeze horizontally
 * on a small screen.
 *
 * <p><strong>Layout invariants relied on by tests</strong>
 * (see AutoStartTest / AddButtonSmokeTest):
 * <ul>
 *   <li>The first three {@code TextField}s in tree order are the Add-form's
 *       Name / Host / Port fields.</li>
 *   <li>The first {@code Button} in tree order is the '+' Add button.</li>
 *   <li>Per-row ChoiceBoxes are the MsgType picker; a fresh pane has ZERO
 *       ChoiceBoxes until a row is added. Tests recollect after Add.</li>
 *   <li>The last {@code ListView} in tree order is the event stream.</li>
 * </ul>
 */
public class TransmittersPane extends BorderPane {

    // ---------- Column widths (shared between header + card row 1) ----------
    private static final double W_ACTIONS  = 90;   // PLAY + SQUARE
    private static final double W_NAME     = 130;
    private static final double W_HOST     = 120;
    private static final double W_PORT     = 60;
    private static final double W_ENFORCE  = 120;  // "Enforce" label + checkbox
    private static final double W_CONFIG   = 80;   // COG + TRASH_2

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ---------- Model ----------
    private final ObservableList<TxRow> rows = FXCollections.observableArrayList();
    private final Map<String, TxHandle> live = new HashMap<>();
    private final Map<String, DetectionGenerator> generators = new HashMap<>();
    private final Map<String, SensorGenerator> sensorGenerators = new HashMap<>();

    /** Currently-selected card's TxRow, or null. Drives the pane-level Stop button. */
    private final SimpleObjectProperty<TxRow> selectedRow = new SimpleObjectProperty<>(null);

    /** Set by SapientHarnessApp to persist on every mutation. */
    private Runnable persistNow = () -> { };

    // ---------- Event stream ----------
    private final ObservableList<String> stream = FXCollections.observableArrayList();

    // ---------- Card container (package-private for test access) ----------
    /**
     * VBox that holds one {@link TxCard} per {@link TxRow} in {@link #rows}.
     * Kept package-private so smoke tests can traverse it directly without
     * having to wait for a Stage-driven ScrollPane skin to instantiate.
     */
    final VBox cardsBox = new VBox(6);

    // ---------- Enum ----------

    /**
     * Message types the per-row picker can send. Note: AUTO_START isn't a
     * single message — it's a cascade (Registration → wait for RegistrationAck
     * → sensor + detection generators). See {@link #handleSend} + Auto-Start
     * flow below.
     */
    public enum MsgType {
        AUTO_START("Auto-Start (Register + Sensor + Detections)"),
        REGISTRATION("Registration"),
        REGISTRATION_ACK("RegistrationAck"),
        STATUS_REPORT("StatusReport"),
        DETECTION_GENERATOR("Detection Report (generator…)"),
        SENSOR_GENERATOR("Sensor status (generator…)"),
        TASK("Task"),
        TASK_ACK("TaskAck"),
        ALERT("Alert"),
        ALERT_ACK("AlertAck"),
        ERROR("Error");
        private final String label;
        MsgType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    // =================================================================
    //                       Constructor / layout
    // =================================================================
    public TransmittersPane() {
        setPadding(new Insets(8));

        // ---- Add-form row (Name / Host / Port / + Add) ----
        TextField nameField = new TextField();
        nameField.setPromptText("name");
        nameField.setPrefWidth(140);
        TextField hostField = new TextField();
        hostField.setPromptText("host");
        hostField.setPrefWidth(120);
        TextField portField = new TextField();
        portField.setPromptText("port");
        portField.setPrefWidth(70);
        Button addBtn = Icons.accentIconButton(Feather.PLUS, "Add transmitter");
        Label formHint = new Label();
        formHint.setStyle("-fx-text-fill: -color-danger-fg;");
        // Clear the hint the moment the user starts typing so it doesn't
        // stick around after they've addressed the missing fields.
        ChangeListener<String> hintClear = (obs, o, n) -> formHint.setText("");
        nameField.textProperty().addListener(hintClear);
        hostField.textProperty().addListener(hintClear);
        portField.textProperty().addListener(hintClear);
        addBtn.setOnAction(e -> handleAdd(nameField, hostField, portField, formHint));
        HBox addForm = new HBox(6,
                new Label("Name"), nameField,
                new Label("Host"), hostField,
                new Label("Port"), portField,
                addBtn, formHint);
        addForm.setAlignment(Pos.CENTER_LEFT);
        addForm.setPadding(new Insets(0, 0, 6, 0));

        // ---- Column header row ----
        HBox headerRow = buildHeaderRow();

        // ---- Pane-level "Stop generators on selected transmitter" ----
        // Use OCTAGON (stop-sign shape) not SQUARE so this pane-level
        // Stop-generators button doesn't visually collide with the row-
        // level Disconnect button. Same reasoning as the disconnect-icon
        // change (Marty 2026-08-05 12:01 UTC).
        Button stopGenBtn = Icons.dangerIconButton(Feather.OCTAGON,
                "Stop generators on the selected transmitter");
        stopGenBtn.setOnAction(e -> {
            TxRow row = selectedRow.get();
            if (row == null) { append(null, "! no transmitter selected"); return; }
            boolean did = false;
            if (row.generating.get()) { stopGenerator(row); did = true; }
            if (sensorGenerators.containsKey(row.name.get())) { stopSensorGenerator(row); did = true; }
            if (!did) append(row, "! no generators running on this row");
        });
        // Enable only when something is selected AND either generator is running.
        stopGenBtn.disableProperty().bind(selectedRow.isNull());
        HBox toolRow = new HBox(6, new Label("Selection tools:"), stopGenBtn);
        toolRow.setAlignment(Pos.CENTER_LEFT);
        toolRow.setPadding(new Insets(0, 0, 6, 0));

        // ---- Cards container (VBox inside ScrollPane) ----
        ScrollPane scroll = new ScrollPane(cardsBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        cardsBox.setPadding(new Insets(2, 0, 2, 0));

        // Rebuild cards on any change to the rows list (add, remove, set).
        rows.addListener((javafx.collections.ListChangeListener<TxRow>) c -> rebuildCards());
        // Re-paint selection highlight when selectedRow changes.
        selectedRow.addListener((obs, oldR, newR) -> applySelectionStyling());

        // ---- Event stream (log panel) — Clear button + tight cells ----
        ListView<String> streamView = new ListView<>(stream);
        streamView.setPrefHeight(200);
        streamView.setStyle("-fx-cell-size: 20px;");
        streamView.getStylesheets().add(
                "data:text/css," + java.net.URLEncoder.encode(
                        ".list-cell { -fx-padding: 1 6 1 6; }",
                        java.nio.charset.StandardCharsets.UTF_8));
        Button clearStreamBtn = Icons.dangerIconButton(Feather.TRASH_2,
                "Clear the event stream");
        clearStreamBtn.setFocusTraversable(false);
        clearStreamBtn.setOnAction(e -> stream.clear());
        HBox streamHeader = new HBox(8, new Label("Event stream"), spacer(), clearStreamBtn);
        streamHeader.setAlignment(Pos.CENTER_LEFT);
        streamHeader.setPadding(new Insets(4, 4, 4, 4));
        VBox streamBox = new VBox(streamHeader, streamView);
        VBox.setVgrow(streamView, Priority.ALWAYS);

        // ---- Assemble ----
        VBox top = new VBox(4, addForm, headerRow, toolRow);
        setTop(top);
        setCenter(scroll);
        setBottom(streamBox);
    }

    // =================================================================
    //                          Add-form handling
    // =================================================================

    private void handleAdd(TextField nameField, TextField hostField,
                           TextField portField, Label formHint) {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String host = hostField.getText() == null ? "" : hostField.getText().trim();
        String portText = portField.getText() == null ? "" : portField.getText().trim();

        List<String> missing = new ArrayList<>();
        if (name.isEmpty()) missing.add("name");
        if (host.isEmpty()) missing.add("host");
        if (portText.isEmpty()) missing.add("port");
        if (!missing.isEmpty()) {
            formHint.setText("missing: " + String.join(", ", missing));
            return;
        }
        int port;
        try { port = Integer.parseInt(portText); }
        catch (NumberFormatException nfe) { formHint.setText("port must be an integer"); return; }
        if (port <= 0 || port > 65535) { formHint.setText("port must be 1..65535"); return; }

        for (TxRow r : rows) {
            if (r.name.get().equalsIgnoreCase(name)) {
                formHint.setText("duplicate name: " + name);
                return;
            }
        }

        TxRow row = new TxRow(name, host, port);
        rows.add(row);
        nameField.clear(); hostField.clear(); portField.clear();
        formHint.setText("");
        append(row, "＋ added transmitter " + name + " → " + host + ":" + port
                + " (nodeId=" + row.nodeId + ")");
        persistNow.run();
    }

    // =================================================================
    //                       Header + card rebuild
    // =================================================================

    private HBox buildHeaderRow() {
        HBox h = new HBox(6);
        h.setPadding(new Insets(6, 4, 4, 4));
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("card-header");
        h.setStyle("-fx-border-color: -color-border-default;"
                + " -fx-border-width: 0 0 1 0;");
        h.getChildren().addAll(
                headerLabel("Actions",  W_ACTIONS),
                headerLabel("Name",     W_NAME),
                headerLabel("Host",     W_HOST),
                headerLabel("Port",     W_PORT),
                headerLabelGrow("Message"),
                headerLabel("Enforce",  W_ENFORCE),
                headerLabel("Config",   W_CONFIG));
        return h;
    }

    private static Label headerLabel(String text, double width) {
        Label l = new Label(text);
        l.setMinWidth(width);
        l.setPrefWidth(width);
        l.setMaxWidth(width);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private static Label headerLabelGrow(String text) {
        Label l = new Label(text);
        HBox.setHgrow(l, Priority.ALWAYS);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Full rebuild of {@link #cardsBox}. Cheap at N &lt; 50 transmitters. */
    private void rebuildCards() {
        cardsBox.getChildren().clear();
        for (TxRow r : rows) cardsBox.getChildren().add(new TxCard(r));
        applySelectionStyling();
    }

    private void applySelectionStyling() {
        TxRow sel = selectedRow.get();
        for (javafx.scene.Node n : cardsBox.getChildren()) {
            if (n instanceof TxCard c) c.setSelected(c.row == sel);
        }
    }

    // =================================================================
    //                       Send / Zap handlers
    // =================================================================

    private void handleSend(TxRow row, MsgType type) {
        if (row == null) { append(null, "! no transmitter selected"); return; }
        if (type == null) { append(row, "! no message type selected"); return; }
        String nodeId = row.nodeId;
        switch (type) {
            case AUTO_START          -> handleAutoStart(row);
            case REGISTRATION        -> withMsg(row, MessageDialogs.registration(nodeId));
            case REGISTRATION_ACK    -> withMsg(row, MessageDialogs.registrationAck(nodeId));
            case STATUS_REPORT       -> withMsg(row, MessageDialogs.statusReport(nodeId));
            case DETECTION_GENERATOR -> startGenerator(row);
            case SENSOR_GENERATOR    -> startSensorGenerator(row);
            case TASK                -> withMsg(row, MessageDialogs.task(nodeId));
            case TASK_ACK            -> withMsg(row, MessageDialogs.taskAck(nodeId));
            case ALERT               -> withMsg(row, MessageDialogs.alert(nodeId));
            case ALERT_ACK           -> withMsg(row, MessageDialogs.alertAck(nodeId));
            case ERROR               -> withMsg(row, MessageDialogs.error(nodeId));
        }
    }

    /**
     * Auto-Start cascade: verify strict mode, connect, wait for
     * REGISTERED, then start sensor + detection generators.
     */
    private void handleAutoStart(TxRow row) {
        if (!row.enforceHandshake.get()) {
            append(row, "! auto-start requires strict mode "
                    + "(enable Enforce handshake first — the receiver's "
                    + "RegistrationAck is what triggers generator launch)");
            return;
        }
        if (live.containsKey(row.name.get())) {
            if (live.get(row.name.get()).isConnected()) {
                append(row, "◆ AUTO-START: already REGISTERED, launching generators");
                launchAutoStartGenerators(row);
                return;
            } else {
                append(row, "! auto-start: row has a stale handle — Disconnect first");
                return;
            }
        }
        // Attach a one-shot status listener that fires the generators when
        // the row becomes 'connected' (== REGISTERED for strict), then unhooks
        // itself. Also bails out on error/disconnect transitions.
        ChangeListener<String> waiter = new ChangeListener<>() {
            @Override public void changed(javafx.beans.value.ObservableValue<? extends String> obs,
                                          String oldV, String newV) {
                if (newV == null) return;
                if ("connected".equals(newV)) {
                    row.status.removeListener(this);
                    Platform.runLater(() -> {
                        append(row, "◆ AUTO-START: handshake complete, launching generators");
                        launchAutoStartGenerators(row);
                    });
                } else if (newV.startsWith("error")) {
                    row.status.removeListener(this);
                    Platform.runLater(() -> append(row,
                            "◆ AUTO-START: aborted — handshake failed (" + newV + ")"));
                }
            }
        };
        row.status.addListener(waiter);
        if ("connected".equals(row.status.get())) {
            row.status.removeListener(waiter);
            append(row, "◆ AUTO-START: already registered (post-hook race), launching generators");
            launchAutoStartGenerators(row);
            return;
        }
        append(row, "◆ AUTO-START: initiating Connect — waiting for Registration → RegistrationAck");
        connect(row);
    }

    private void launchAutoStartGenerators(TxRow row) {
        TxHandle tx = live.get(row.name.get());
        if (tx == null || !tx.isConnected()) {
            append(row, "◆ AUTO-START: connection dropped before generators could start");
            return;
        }
        // Sensor first (sends StatusReport with the sensor's own location).
        try {
            SensorGenerator.Config sensorCfg = AutoStartRecipe.sensorConfig(row.nodeId);
            SensorGenerator sensor = new SensorGenerator(sensorCfg, msg -> {
                try {
                    tx.send(msg);
                    Platform.runLater(() -> {
                        row.sent.set(row.sent.get() + 1);
                        if (row.sent.get() % 10 == 0)
                            append(row, "⇒ sensor sent " + row.sent.get() + " status reports");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> append(row,
                            "! auto-start sensor send failed: " + ex.getMessage()));
                }
            });
            sensorGenerators.put(row.name.get(), sensor);
            sensor.start();
            append(row, "📡 AUTO-START: sensor generator running (moving, CONE FOV, 20 km range)");
        } catch (Exception ex) {
            append(row, "! auto-start: sensor generator failed to start: " + ex.getMessage());
            return;
        }
        // Detection generator.
        try {
            DetectionGenerator.Config detCfg = AutoStartRecipe.detectionConfig(row.nodeId);
            DetectionGenerator gen = new DetectionGenerator(detCfg, msg -> {
                try {
                    tx.send(msg);
                    Platform.runLater(() -> {
                        row.sent.set(row.sent.get() + 1);
                        if (row.sent.get() % 20 == 0)
                            append(row, "→ detection generator sent " + row.sent.get() + " tracks");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> append(row,
                            "! auto-start detection send failed: " + ex.getMessage()));
                }
            });
            generators.put(row.name.get(), gen);
            gen.start();
            row.generating.set(true);
            row.activity.set(String.format("▶ auto-start: %d drones @ %d ms (20 km, moving)",
                    detCfg.trackCount, detCfg.tickMs));
            append(row, "▶ AUTO-START: detection generator running (" + detCfg.trackCount
                    + " drones, 20 km radius, moving)");
        } catch (Exception ex) {
            append(row, "! auto-start: detection generator failed to start: " + ex.getMessage());
            return;
        }
        append(row, "◆ AUTO-START: cascade complete — sensor + detections running until Stop / Disconnect");
    }

    private void withMsg(TxRow row, Optional<SapientMessage> msgOpt) {
        msgOpt.ifPresent(m -> sendOne(row, m));
    }

    private void sendOne(TxRow row, SapientMessage msg) {
        TxHandle tx = live.get(row.name.get());
        if (tx == null || !tx.isConnected()) {
            append(row, "! not connected — click Connect first");
            return;
        }
        try {
            tx.send(msg);
            row.sent.set(row.sent.get() + 1);
            append(row, "→ SENT " + msg.getContentCase().name());
        } catch (IllegalStateException ise) {
            append(row, "! blocked by handshake: " + ise.getMessage()
                    + " (BSI Flex 335 v2.0 §6.2.2)");
        } catch (Exception ex) {
            append(row, "! send failed: " + ex.getMessage());
        }
    }

    /** Zap (⚡): fire a minimal Registration for this row, no dialog. */
    private void quickRegistration(TxRow row) {
        if (row == null) return;
        try {
            SapientMessage msg = MessageFactory.registration(row.nodeId,
                    Registration.NodeType.NODE_TYPE_OTHER, "quick-smoke");
            sendOne(row, msg);
        } catch (Exception ex) {
            append(row, "! quick-registration failed: " + ex.getMessage());
        }
    }

    // =================================================================
    //                     Generator lifecycle
    // =================================================================

    private void startGenerator(TxRow row) {
        TxHandle tx = live.get(row.name.get());
        if (tx == null || !tx.isConnected()) { append(row, "! not connected — click Connect first"); return; }
        if (generators.containsKey(row.name.get())) { append(row, "! generator already running — stop it first"); return; }
        Optional<DetectionGenerator.Config> cfgOpt = MessageDialogs.detectionGenerator(row.nodeId);
        if (cfgOpt.isEmpty()) return;
        DetectionGenerator.Config cfg = cfgOpt.get();

        DetectionGenerator gen = new DetectionGenerator(cfg, msg -> {
            try {
                tx.send(msg);
                Platform.runLater(() -> {
                    row.sent.set(row.sent.get() + 1);
                    if (row.sent.get() % 20 == 0)
                        append(row, "→ generator sent " + row.sent.get() + " detections");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> append(row, "! generator send failed: " + ex.getMessage()));
            }
        });
        generators.put(row.name.get(), gen);
        gen.start();
        row.generating.set(true);
        String altSummary = "";
        if (cfg.initialAltitudeM != 0.0 || cfg.altitudeJitterM != 0.0 || cfg.verticalRateMps != 0.0) {
            altSummary = String.format(" alt=%.0f±%.0f m%s",
                    cfg.initialAltitudeM, cfg.altitudeJitterM,
                    cfg.verticalRateMps > 0 ? String.format(" ±%.1f m/s", cfg.verticalRateMps) : "");
        }
        row.activity.set(String.format("▶ generating: %d tracks @ %d ms%s%s",
                cfg.trackCount, cfg.tickMs, cfg.moving ? " (moving)" : "", altSummary));
        append(row, "▶ started detection generator: " + cfg.trackCount + " tracks, "
                + cfg.tickMs + " ms rate, moving=" + cfg.moving + altSummary);
    }

    private void stopGenerator(TxRow row) {
        DetectionGenerator gen = generators.remove(row.name.get());
        if (gen != null) {
            gen.stop();
            row.generating.set(false);
            row.activity.set("");
            append(row, "■ stopped detection generator");
        }
    }

    private void startSensorGenerator(TxRow row) {
        TxHandle tx = live.get(row.name.get());
        if (tx == null || !tx.isConnected()) { append(row, "! not connected — click Connect first"); return; }
        if (sensorGenerators.containsKey(row.name.get())) { append(row, "! sensor generator already running"); return; }
        Optional<SensorGenerator.Config> cfgOpt = MessageDialogs.sensorGenerator(row.nodeId);
        if (cfgOpt.isEmpty()) return;
        SensorGenerator.Config cfg = cfgOpt.get();
        SensorGenerator sensor = new SensorGenerator(cfg, msg -> {
            try {
                tx.send(msg);
                Platform.runLater(() -> {
                    row.sent.set(row.sent.get() + 1);
                    if (row.sent.get() % 10 == 0)
                        append(row, "⇒ sensor sent " + row.sent.get() + " status reports");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> append(row, "! sensor generator send failed: " + ex.getMessage()));
            }
        });
        sensorGenerators.put(row.name.get(), sensor);
        sensor.start();
        String fov = switch (cfg.fovMode) {
            case CONE -> "cone";
            case POLYGON -> "polygon";
            case POLYGON_WITH_OBSCURATION -> "polygon+obscured";
        };
        append(row, "📡 started sensor generator: " + fov + " FOV, "
                + cfg.rangeMeters + " m range @ " + cfg.tickMs + " ms");
    }

    private void stopSensorGenerator(TxRow row) {
        SensorGenerator s = sensorGenerators.remove(row.name.get());
        if (s != null) {
            s.stop();
            append(row, "■ stopped sensor generator");
        }
    }

    // =================================================================
    //                       Connect / disconnect
    // =================================================================

    private void connect(TxRow row) {
        if (row == null || live.containsKey(row.name.get())) return;
        SapientMessageListener listener = new SapientMessageListener() {
            @Override public void onConnected(SocketAddress peer) {
                Platform.runLater(() -> { row.status.set("connected"); append(row, "→ CONNECTED to " + peer); });
            }
            @Override public void onDisconnected(SocketAddress peer) {
                Platform.runLater(() -> { row.status.set("disconnected"); append(row, "→ DISCONNECTED from " + peer); });
            }
            @Override public void onMessage(SocketAddress peer, SapientMessage msg) {
                Platform.runLater(() -> append(row,
                        "← REPLY " + msg.getContentCase().name() + " from " + msg.getNodeId()));
            }
            @Override public void onError(SocketAddress peer, Throwable cause) {
                Platform.runLater(() -> append(row, "! ERROR: " + cause));
            }
        };
        boolean enforce = row.enforceHandshake.get();
        if (enforce) {
            NodeIdentity id = new NodeIdentity(row.nodeId,
                    Registration.NodeType.NODE_TYPE_OTHER,
                    NodeIdentity.ICD_VERSION,
                    NodeIdentity.DEFAULT_STATUS_INTERVAL);
            SapientEdgeClient.Listener edgeListener = new SapientEdgeClient.Listener() {
                @Override public void onStateChanged(HandshakeState newState) {
                    Platform.runLater(() -> {
                        String label = switch (newState) {
                            case NEW         -> "connecting";
                            case REGISTERING -> "registering";
                            case REGISTERED  -> "connected";
                            case REJECTED    -> "error: registration rejected";
                            case GOODBYE     -> "disconnecting";
                            case CLOSED      -> "disconnected";
                        };
                        row.status.set(label);
                        append(row, "◎ handshake state = " + newState);
                    });
                }
                @Override public void onMessage(SocketAddress peer, SapientMessage msg) {
                    Platform.runLater(() -> append(row,
                            "← REPLY " + msg.getContentCase().name() + " from " + msg.getNodeId()));
                }
                @Override public void onError(SocketAddress peer, Throwable cause) {
                    Platform.runLater(() -> append(row, "! ERROR: " + cause));
                }
            };
            SapientEdgeClient client = SapientEdgeClient.builder(row.host.get(), row.port.get(), id)
                    .name(row.name.get()).listener(edgeListener).build();
            new Thread(() -> {
                try {
                    client.start();
                    live.put(row.name.get(), TxHandle.strict(client));
                    Platform.runLater(() -> append(row,
                            "★ STRICT mode — Registration sent as nodeId=" + row.nodeId));
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        row.status.set("error: " + ex.getMessage());
                        append(row, "! handshake failed: " + ex.getMessage());
                    });
                    try { client.close(); } catch (Exception ignore) { }
                }
            }, "connect-strict-" + row.name.get()).start();
        } else {
            SapientTransmitter tx = new SapientTransmitter(
                    row.name.get(), row.host.get(), row.port.get(), listener);
            new Thread(() -> {
                try {
                    tx.connect();
                    live.put(row.name.get(), TxHandle.dumb(tx));
                } catch (Exception ex) {
                    Platform.runLater(() -> row.status.set("error: " + ex.getMessage()));
                }
            }, "connect-" + row.name.get()).start();
        }
    }

    private void disconnect(TxRow row) {
        if (row == null) return;
        stopGenerator(row);
        stopSensorGenerator(row);
        TxHandle tx = live.remove(row.name.get());
        if (tx != null) new Thread(tx::close, "disconnect-" + row.name.get()).start();
    }

    public void setPersistNow(Runnable persistNow) {
        this.persistNow = (persistNow == null) ? () -> { } : persistNow;
    }

    /** Open Edit dialog; on OK, disconnect + swap the row (nodeId is final). */
    private void editRow(TxRow row) {
        if (row == null) return;
        boolean wasLive = live.containsKey(row.name.get());
        var editOpt = EditDialogs.editTransmitter(
                row.name.get(), row.host.get(), row.port.get(), row.nodeId,
                row.enforceHandshake.get(), wasLive);
        if (editOpt.isEmpty()) return;
        var edit = editOpt.get();
        stopGenerator(row);
        if (wasLive) disconnect(row);
        int idx = rows.indexOf(row);
        boolean wasSelected = (selectedRow.get() == row);
        TxRow replacement = new TxRow(edit.name, edit.host, edit.port, edit.nodeId);
        replacement.status.set("disconnected");
        replacement.enforceHandshake.set(edit.enforceHandshake);
        if (idx >= 0) rows.set(idx, replacement); else rows.add(replacement);
        if (wasSelected) selectedRow.set(replacement);
        append(replacement, "✎ edited — name=" + edit.name + " host=" + edit.host
                + " port=" + edit.port + " nodeId=" + edit.nodeId
                + " enforceHandshake=" + edit.enforceHandshake
                + (wasLive ? " (was connected, now DISCONNECTED — press Connect when ready)" : ""));
        persistNow.run();
    }

    // =================================================================
    //                       Stream + snapshot + shutdown
    // =================================================================

    private void append(TxRow row, String line) {
        String ts = LocalTime.now().format(TS);
        String prefix = (row == null) ? "" : row.name.get() + ": ";
        stream.add(0, "[" + ts + "] " + prefix + line);
        while (stream.size() > 500) stream.remove(stream.size() - 1);
    }

    public List<SessionStore.SavedTransmitter> snapshot() {
        List<SessionStore.SavedTransmitter> out = new ArrayList<>();
        for (TxRow r : rows) {
            out.add(new SessionStore.SavedTransmitter(
                    r.name.get(), r.host.get(), r.port.get(), r.nodeId,
                    r.enforceHandshake.get()));
        }
        return out;
    }

    public void restore(List<SessionStore.SavedTransmitter> saved) {
        if (saved == null) return;
        for (SessionStore.SavedTransmitter s : saved) {
            if (s == null || s.name == null || s.name.isBlank()) continue;
            TxRow row = new TxRow(s.name, s.host, s.port, s.nodeId);
            row.enforceHandshake.set(s.enforceHandshake);
            rows.add(row);
        }
    }

    public void shutdown() {
        for (DetectionGenerator gen : generators.values()) { try { gen.stop(); } catch (Exception ignored) { } }
        generators.clear();
        for (SensorGenerator sg : sensorGenerators.values()) { try { sg.stop(); } catch (Exception ignored) { } }
        sensorGenerators.clear();
        for (TxHandle tx : live.values()) { try { tx.close(); } catch (Exception ignored) { } }
        live.clear();
    }

    // =================================================================
    //                            TxCard
    // =================================================================

    /**
     * A single transmitter's card — two internal HBox rows sharing the
     * pane's fixed column widths so the header stays aligned.
     */
    private final class TxCard extends VBox {
        final TxRow row;
        // PLAY_CIRCLE renders as a filled play button (circle with a
        // triangle inside) so it reads as "fill the arrow buttons" per
        // Marty 2026-08-05 12:26 UTC — Feather's outline glyph looked
        // hollow next to the coloured button background.
        private final Button connectBtn = Icons.iconButton(Feather.PLAY_CIRCLE, "Connect this transmitter");
        // POWER icon (not SQUARE) so the row-level Disconnect is
        // visually distinct from the pane-level Stop-generators button.
        // (2026-08-05 12:01 UTC — "the disconnect button does not work";
        // root cause was actually the two SQUAREs plus the .flat style
        // hiding the semantic state change on the play button.)
        private final Button disconnectBtn = Icons.iconButton(Feather.POWER, "Disconnect this transmitter");

        TxCard(TxRow row) {
            super(2);
            this.row = row;
            setPadding(new Insets(4, 4, 4, 4));
            setDefaultBorder();
            // Click anywhere on the card selects it.
            setOnMouseClicked(e -> selectedRow.set(row));

            // ---- Row 1 ----
            connectBtn.setFocusTraversable(false);
            disconnectBtn.setFocusTraversable(false);
            // Deliberately NOT adding "flat" here — flat overrides the
            // success/danger semantic style classes and washes the button
            // out to a hollow outline, so Marty couldn't tell the state
            // apart after a disconnect (2026-08-05 12:26 UTC screenshot).
            // With no "flat" the AtlantaFX success/danger fills give a
            // solid green/red background so the connect state is obvious.
            paintConnectButton(row.status.get());
            row.status.addListener((obs, o, n) -> paintConnectButton(n));
            connectBtn.setOnAction(e -> { append(row, "◎ Connect clicked"); connect(row); });
            // Log the click into the event stream so it's obvious the click
            // reached the handler even if the underlying transport is stuck.
            disconnectBtn.setOnAction(e -> { append(row, "◎ Disconnect clicked"); disconnect(row); });
            HBox actionsBox = new HBox(4, connectBtn, disconnectBtn);
            actionsBox.setAlignment(Pos.CENTER_LEFT);
            setFixedWidth(actionsBox, W_ACTIONS);

            Label nameLbl = new Label(row.name.get());
            row.name.addListener((obs, o, n) -> nameLbl.setText(n));
            setFixedWidth(nameLbl, W_NAME);

            Label hostLbl = new Label(row.host.get());
            row.host.addListener((obs, o, n) -> hostLbl.setText(n));
            setFixedWidth(hostLbl, W_HOST);

            Label portLbl = new Label(String.valueOf(row.port.get()));
            row.port.addListener((obs, o, n) -> portLbl.setText(String.valueOf(n.intValue())));
            setFixedWidth(portLbl, W_PORT);

            // Per-row Message picker + Send + Zap
            ChoiceBox<MsgType> picker = new ChoiceBox<>();
            picker.getItems().addAll(MsgType.values());
            picker.setValue(MsgType.REGISTRATION);
            picker.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(picker, Priority.ALWAYS);
            Button sendBtn = Icons.accentIconButton(Feather.SEND, "Send selected message");
            sendBtn.setFocusTraversable(false);
            sendBtn.setOnAction(e -> {
                selectedRow.set(row);
                handleSend(row, picker.getValue());
            });
            Button zapBtn = Icons.iconButton(Feather.ZAP, "Quick Registration");
            zapBtn.setFocusTraversable(false);
            zapBtn.getStyleClass().add("flat");
            zapBtn.setOnAction(e -> { selectedRow.set(row); quickRegistration(row); });
            HBox messageBox = new HBox(4, picker, sendBtn, zapBtn);
            messageBox.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(messageBox, Priority.ALWAYS);

            // Enforce checkbox — disabled while connected.
            CheckBox enforceBox = new CheckBox();
            enforceBox.setFocusTraversable(false);
            enforceBox.setSelected(row.enforceHandshake.get());
            enforceBox.setDisable(isConnectedStatus(row.status.get()));
            row.status.addListener((obs, o, n) -> enforceBox.setDisable(isConnectedStatus(n)));
            enforceBox.selectedProperty().addListener((obs, o, n) -> {
                if (n == null || n == row.enforceHandshake.get()) return;
                row.enforceHandshake.set(n);
                append(row, "✎ enforce handshake = " + n);
                persistNow.run();
            });
            HBox enforceHBox = new HBox(4, enforceBox);
            enforceHBox.setAlignment(Pos.CENTER_LEFT);
            setFixedWidth(enforceHBox, W_ENFORCE);

            Button editBtn = Icons.iconButton(Feather.SETTINGS,
                    "Edit — saving disconnects this transmitter");
            Button removeBtn = Icons.dangerIconButton(Feather.TRASH_2, "Remove this transmitter");
            editBtn.setFocusTraversable(false); removeBtn.setFocusTraversable(false);
            editBtn.getStyleClass().add("flat"); removeBtn.getStyleClass().add("flat");
            editBtn.setOnAction(e -> { selectedRow.set(row); editRow(row); });
            removeBtn.setOnAction(e -> {
                stopGenerator(row); disconnect(row);
                if (selectedRow.get() == row) selectedRow.set(null);
                rows.remove(row); persistNow.run();
            });
            HBox configBox = new HBox(4, editBtn, removeBtn);
            configBox.setAlignment(Pos.CENTER_LEFT);
            setFixedWidth(configBox, W_CONFIG);

            HBox row1 = new HBox(6, actionsBox, nameLbl, hostLbl, portLbl,
                    messageBox, enforceHBox, configBox);
            row1.setAlignment(Pos.CENTER_LEFT);

            // ---- Row 2: Status | Sent | Activity ----
            Label statusHdr = new Label("Status:");
            Label statusVal = new Label(row.status.get());
            paintStatus(statusVal, row.status.get());
            row.status.addListener((obs, o, n) -> paintStatus(statusVal, n));
            Label sentHdr = new Label("Sent:");
            Label sentVal = new Label(String.valueOf(row.sent.get()));
            row.sent.addListener((obs, o, n) -> sentVal.setText(String.valueOf(n.intValue())));
            Label activityHdr = new Label("Activity:");
            Label activityVal = new Label(row.activity.get());
            activityVal.setStyle("-fx-text-fill: -color-fg-muted;");
            row.activity.addListener((obs, o, n) -> activityVal.setText(n == null ? "" : n));
            HBox.setHgrow(activityVal, Priority.ALWAYS);
            activityVal.setMaxWidth(Double.MAX_VALUE);
            HBox row2 = new HBox(8, statusHdr, statusVal, spacer(),
                    sentHdr, sentVal, spacer(),
                    activityHdr, activityVal);
            // Indent Row 2 under the Actions column so it visually starts
            // where Name starts on Row 1.
            row2.setPadding(new Insets(0, 0, 2, W_ACTIONS + 6));
            row2.setAlignment(Pos.CENTER_LEFT);
            for (Label l : new Label[] { statusHdr, sentHdr, activityHdr }) {
                l.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
            }

            getChildren().addAll(row1, row2);
        }

        void setSelected(boolean selected) {
            if (selected) {
                setStyle("-fx-border-color: -color-accent-emphasis;"
                        + " -fx-border-width: 1;"
                        + " -fx-background-color: -color-accent-subtle;"
                        + " -fx-background-radius: 3;"
                        + " -fx-border-radius: 3;");
            } else {
                setDefaultBorder();
            }
        }

        private void setDefaultBorder() {
            setStyle("-fx-border-color: -color-border-muted;"
                    + " -fx-border-width: 1;"
                    + " -fx-background-radius: 3;"
                    + " -fx-border-radius: 3;");
        }

        private void paintConnectButton(String status) {
            connectBtn.getStyleClass().removeAll("success", "danger");
            if ("connected".equals(status)) connectBtn.getStyleClass().add("success");
            else connectBtn.getStyleClass().add("danger");
        }

        private void paintStatus(Label lbl, String s) {
            if (s == null) { lbl.setText(""); lbl.setStyle(""); return; }
            lbl.setText(s);
            if ("connected".equals(s)) {
                lbl.setStyle("-fx-text-fill: -color-success-fg; -fx-font-weight: bold;");
            } else if ("connecting".equals(s) || "registering".equals(s)
                    || "disconnecting".equals(s)) {
                lbl.setStyle("-fx-text-fill: -color-warning-fg; -fx-font-weight: bold;");
            } else {
                lbl.setStyle("-fx-text-fill: -color-danger-fg; -fx-font-weight: bold;");
            }
        }

        private boolean isConnectedStatus(String s) {
            return "connected".equals(s) || "registering".equals(s)
                    || "connecting".equals(s) || "disconnecting".equals(s);
        }

        private void setFixedWidth(Region r, double w) {
            r.setMinWidth(w); r.setPrefWidth(w); r.setMaxWidth(w);
        }
    }

    // =================================================================
    //                       TxRow + TxHandle (unchanged)
    // =================================================================

    static final class TxRow {
        final SimpleStringProperty name;
        final SimpleStringProperty host;
        final SimpleIntegerProperty port;
        final SimpleStringProperty status;
        final SimpleIntegerProperty sent;
        final SimpleStringProperty activity;
        final SimpleBooleanProperty generating;
        final String nodeId;
        final SimpleBooleanProperty enforceHandshake;

        TxRow(String name, String host, int port) { this(name, host, port, UUID.randomUUID().toString()); }
        TxRow(String name, String host, int port, String nodeId) {
            this.name = new SimpleStringProperty(name);
            this.host = new SimpleStringProperty(host);
            this.port = new SimpleIntegerProperty(port);
            this.status = new SimpleStringProperty("disconnected");
            this.sent = new SimpleIntegerProperty(0);
            this.activity = new SimpleStringProperty("");
            this.generating = new SimpleBooleanProperty(false);
            this.nodeId = (nodeId == null || nodeId.isBlank()) ? UUID.randomUUID().toString() : nodeId;
            this.enforceHandshake = new SimpleBooleanProperty(false);
        }
    }

    static final class TxHandle {
        private final SapientTransmitter dumb;
        private final SapientEdgeClient strict;
        private TxHandle(SapientTransmitter dumb, SapientEdgeClient strict) { this.dumb = dumb; this.strict = strict; }
        static TxHandle dumb(SapientTransmitter tx) { return new TxHandle(tx, null); }
        static TxHandle strict(SapientEdgeClient client) { return new TxHandle(null, client); }
        boolean isStrict() { return strict != null; }
        boolean isConnected() {
            if (strict != null) return strict.isRegistered();
            return dumb != null && dumb.isConnected();
        }
        void send(SapientMessage msg) {
            if (strict != null) strict.send(msg); else dumb.send(msg);
        }
        void close() {
            if (strict != null) strict.close(); else if (dumb != null) dumb.close();
        }
    }
}
