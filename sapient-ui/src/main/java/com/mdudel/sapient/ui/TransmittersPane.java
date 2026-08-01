/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.gen.DetectionGenerator;
import com.mdudel.sapient.core.gen.SensorGenerator;
import com.mdudel.sapient.core.template.MessageTemplateLoader;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientTransmitter;
import com.mdudel.sapient.ui.dialog.EditDialogs;
import com.mdudel.sapient.ui.dialog.MessageDialogs;
import com.mdudel.sapient.ui.persist.SessionStore;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

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
 * "Transmitters" tab.
 *
 * <p>Top: table of configured transmitters (name, host, port, status, activity).
 * Middle: message-type picker + Send button that dispatches to a per-type dialog.
 * Bottom: live event stream.
 *
 * <p>Detection generator is a scheduled activity — starting it puts the
 * transmitter into a "generating" state visible in the Activity column of
 * the table, with a stop icon in that cell to halt it.
 */
public final class TransmittersPane extends BorderPane {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Message type entries for the picker dropdown. */
    private enum MsgType {
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

        final String label;
        MsgType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final ObservableList<TxRow> rows = FXCollections.observableArrayList();
    private final Map<String, SapientTransmitter> live = new HashMap<>();
    private final Map<String, DetectionGenerator> generators = new HashMap<>();
    /** Parallel to {@code generators}: one sensor generator per transmitter row. */
    private final Map<String, SensorGenerator> sensorGenerators = new HashMap<>();
    private final ObservableList<String> stream = FXCollections.observableArrayList();

    /**
     * Optional persistence hook: invoked whenever the transmitter list
     * changes shape or a row is edited, so the JSON session file stays
     * current without waiting for window close. Wired by
     * {@code SapientHarnessApp}. Null-safe.
     */
    private Runnable persistNow = () -> { };

    public TransmittersPane() {
        setPadding(new Insets(8));

        // --- Top: form + table ---
        TextField nameField = new TextField();
        nameField.setPromptText("Name (e.g. tx-to-dmm)");
        TextField hostField = new TextField();
        hostField.setPromptText("Host (e.g. 10.0.0.20)");
        TextField portField = new TextField();
        portField.setPromptText("Port (e.g. 14000)");
        Button addBtn = Icons.accentIconButton(Feather.PLUS, "Add transmitter");
        addBtn.setFocusTraversable(false);
        HBox form = new HBox(6, nameField, hostField, portField, addBtn);
        form.setAlignment(Pos.CENTER_LEFT);
        form.setPadding(new Insets(0, 0, 8, 0));

        TableView<TxRow> table = new TableView<>(rows);
        TableColumn<TxRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(d -> d.getValue().name);
        nameCol.setPrefWidth(130);
        TableColumn<TxRow, String> hostCol = new TableColumn<>("Host");
        hostCol.setCellValueFactory(d -> d.getValue().host);
        hostCol.setPrefWidth(130);
        TableColumn<TxRow, Number> portCol = new TableColumn<>("Port");
        portCol.setCellValueFactory(d -> d.getValue().port);
        portCol.setPrefWidth(70);
        TableColumn<TxRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(d -> d.getValue().status);
        statusCol.setPrefWidth(120);
        // 2026-08-01 (SkyLord): status text is red when the transmitter is
        // NOT connected and green when it IS. Uses AtlantaFX 'success' /
        // 'danger' style classes on the cell so the actual shade follows
        // whichever theme (Primer / Nord / Cupertino / Dracula ...) is
        // active — harmonious in every palette.
        statusCol.setCellFactory(makeStatusCellFactory());
        TableColumn<TxRow, Number> countCol = new TableColumn<>("Sent");
        countCol.setCellValueFactory(d -> d.getValue().sent);
        countCol.setPrefWidth(70);
        TableColumn<TxRow, String> activityCol = new TableColumn<>("Activity");
        // Bind to the activity STRING so the cell re-renders whenever activity
        // changes; the cell factory reads .generating for the stop-icon toggle.
        activityCol.setCellValueFactory(d -> d.getValue().activity);
        activityCol.setPrefWidth(220);
        activityCol.setCellFactory(makeActivityCellFactory());
        TableColumn<TxRow, Void> actionsCol = new TableColumn<>("Actions");
        actionsCol.setCellFactory(makeActionsCellFactory());
        actionsCol.setPrefWidth(170);
        actionsCol.setSortable(false);
        table.getColumns().addAll(nameCol, hostCol, portCol, statusCol, countCol,
                activityCol, actionsCol);
        table.setPrefHeight(200);

        VBox top = new VBox(new Label("Configured transmitters"), form, table);

        // --- Middle: message-type picker + send controls ---
        ChoiceBox<MsgType> typePicker = new ChoiceBox<>(FXCollections.observableArrayList(MsgType.values()));
        typePicker.setValue(MsgType.REGISTRATION);
        Button sendBtn = Icons.accentIconButton(Feather.SEND, "Configure and send the selected message type");
        Button quickRegBtn = Icons.iconButton(Feather.ZAP,
                "Quick send: minimal Registration with no dialog (smoke test)");
        Button stopGenBtn = Icons.dangerIconButton(Feather.SQUARE,
                "Stop ALL generators (detection + sensor) running on the selected transmitter");
        // Ditto: don't steal focus from the table selection.
        sendBtn.setFocusTraversable(false);
        quickRegBtn.setFocusTraversable(false);
        stopGenBtn.setFocusTraversable(false);
        typePicker.setFocusTraversable(false);

        HBox sendRow = new HBox(8,
                new Label("Message type:"), typePicker,
                sendBtn, quickRegBtn,
                new javafx.scene.layout.Region() {{
                    javafx.scene.layout.HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS);
                }},
                new Label("Detection generator:"), stopGenBtn);
        sendRow.setAlignment(Pos.CENTER_LEFT);
        sendRow.setPadding(new Insets(8, 0, 8, 0));

        Label helpText = new Label(
                "Select a transmitter above, pick a message type, and click Send. "
              + "A dialog will collect the message-specific fields.\n"
              + "The Detection generator is a scheduled activity — an icon will appear "
              + "in the Activity column to stop it.");
        helpText.setWrapText(true);
        helpText.setPadding(new Insets(0, 0, 8, 0));
        helpText.getStyleClass().add("text-muted");

        VBox middle = new VBox(sendRow, helpText);

        // --- Bottom: event stream ---
        ListView<String> streamView = new ListView<>(stream);
        streamView.setPrefHeight(200);

        setTop(top);
        setCenter(middle);
        setBottom(streamView);

        // --- Wire up: Add / Connect / Disconnect / Remove ---
        addBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            String host = hostField.getText().trim();
            String portText = portField.getText().trim();
            if (name.isEmpty() || host.isEmpty() || portText.isEmpty()) return;
            int port;
            try { port = Integer.parseInt(portText); }
            catch (NumberFormatException ex) { return; }
            TxRow newRow = new TxRow(name, host, port);
            rows.add(newRow);
            table.getSelectionModel().select(newRow);   // <-- auto-select so the next Send lands
            nameField.clear();
            hostField.clear();
            portField.clear();
            persistNow.run();
        });



        // --- Wire up: message send ---
        sendBtn.setOnAction(e -> {
            TxRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) {
                append(null, "! no transmitter selected");
                return;
            }
            MsgType type = typePicker.getValue();
            handleSend(row, type);
        });

        // Stop ALL generators (detection + sensor) on the selected transmitter.
        // Broadened 2026-08-01 when the sensor generator landed; single stop
        // button halts both kinds so the operator doesn't have to remember
        // which is running. Silent-no-op is fine when nothing's running.
        stopGenBtn.setOnAction(e -> {
            TxRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) {
                append(null, "! no transmitter selected");
                return;
            }
            boolean anythingStopped = false;
            if (row.generating.get()) {
                stopGenerator(row);
                anythingStopped = true;
            }
            if (sensorGenerators.containsKey(row.name.get())) {
                stopSensorGenerator(row);
                anythingStopped = true;
            }
            if (!anythingStopped) {
                append(row, "! no generators (detection or sensor) running on this transmitter");
            }
        });

        // Quick send: pre-canned minimal Registration for smoke tests
        quickRegBtn.setOnAction(e -> {
            TxRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) {
                append(null, "! no transmitter selected");
                return;
            }
            sendOne(row, com.mdudel.sapient.core.factory.MessageFactory.registration(
                    UUID.randomUUID().toString(),
                    uk.gov.dstl.sapientmsg.bsiflex335v2.Registration.NodeType.NODE_TYPE_OTHER,
                    "quick-smoke"));
        });
    }

    // ---------- Send dispatch ----------

    private void handleSend(TxRow row, MsgType type) {
        String nodeId = row.nodeId; // stable per row
        switch (type) {
            case REGISTRATION      -> withMsg(row, MessageDialogs.registration(nodeId));
            case REGISTRATION_ACK  -> withMsg(row, MessageDialogs.registrationAck(nodeId));
            case STATUS_REPORT     -> withMsg(row, MessageDialogs.statusReport(nodeId));
            case DETECTION_GENERATOR -> startGenerator(row);
            case SENSOR_GENERATOR  -> startSensorGenerator(row);
            case TASK              -> withMsg(row, MessageDialogs.task(nodeId));
            case TASK_ACK          -> withMsg(row, MessageDialogs.taskAck(nodeId));
            case ALERT             -> withMsg(row, MessageDialogs.alert(nodeId));
            case ALERT_ACK         -> withMsg(row, MessageDialogs.alertAck(nodeId));
            case ERROR             -> withMsg(row, MessageDialogs.error(nodeId));
        }
    }

    private void withMsg(TxRow row, Optional<SapientMessage> msgOpt) {
        msgOpt.ifPresent(m -> sendOne(row, m));
    }

    private void sendOne(TxRow row, SapientMessage msg) {
        SapientTransmitter tx = live.get(row.name.get());
        if (tx == null || !tx.isConnected()) {
            append(row, "! not connected — click Connect first");
            return;
        }
        try {
            tx.send(msg);
            row.sent.set(row.sent.get() + 1);
            append(row, "→ SENT " + msg.getContentCase().name());
        } catch (Exception ex) {
            append(row, "! send failed: " + ex.getMessage());
        }
    }

    // ---------- Detection generator lifecycle ----------

    private void startGenerator(TxRow row) {
        SapientTransmitter tx = live.get(row.name.get());
        if (tx == null || !tx.isConnected()) {
            append(row, "! not connected — click Connect first");
            return;
        }
        if (generators.containsKey(row.name.get())) {
            append(row, "! generator already running — stop it first");
            return;
        }
        Optional<DetectionGenerator.Config> cfgOpt =
                MessageDialogs.detectionGenerator(row.nodeId);
        if (cfgOpt.isEmpty()) return;
        DetectionGenerator.Config cfg = cfgOpt.get();

        DetectionGenerator gen = new DetectionGenerator(cfg, msg -> {
            try {
                tx.send(msg);
                Platform.runLater(() -> {
                    row.sent.set(row.sent.get() + 1);
                    // Update every 20th sent to avoid stream spam
                    if (row.sent.get() % 20 == 0) {
                        append(row, "→ generator sent " + row.sent.get() + " detections");
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> append(row,
                        "! generator send failed: " + ex.getMessage()));
            }
        });
        generators.put(row.name.get(), gen);
        gen.start();
        row.generating.set(true);
        // Prefix with an emoji so the cell contents change visibly even if
        // the inline stop-icon renderer misfires on some JavaFX versions.
        // Altitude summary for the activity chip: only include it when the
        // operator actually dialled altitude in (any of initial / jitter /
        // vertical rate > 0). Keeps the flat-ground default log lines tidy.
        String altSummary = "";
        if (cfg.initialAltitudeM != 0.0 || cfg.altitudeJitterM != 0.0
                || cfg.verticalRateMps != 0.0) {
            altSummary = String.format(" alt=%.0f±%.0f m%s",
                    cfg.initialAltitudeM, cfg.altitudeJitterM,
                    cfg.verticalRateMps > 0
                            ? String.format(" ±%.1f m/s", cfg.verticalRateMps)
                            : "");
        }
        row.activity.set(String.format("▶ generating: %d tracks @ %d ms%s%s",
                cfg.trackCount, cfg.tickMs, cfg.moving ? " (moving)" : "",
                altSummary));
        append(row, "▶ started detection generator: " + cfg.trackCount
                + " tracks, " + cfg.tickMs + " ms rate, moving=" + cfg.moving
                + altSummary);
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

    // ---------- Sensor generator lifecycle ----------

    /**
     * Start a {@link SensorGenerator} on the given row after prompting for
     * config. Mirrors {@link #startGenerator} but for StatusReport / FOV
     * ticks rather than DetectionReports. Idempotent: refuses to start a
     * second sensor generator on a row that already has one — the operator
     * has to stop the running one first.
     */
    private void startSensorGenerator(TxRow row) {
        SapientTransmitter tx = live.get(row.name.get());
        if (tx == null || !tx.isConnected()) {
            append(row, "! not connected — click Connect first");
            return;
        }
        if (sensorGenerators.containsKey(row.name.get())) {
            append(row, "! sensor generator already running — stop it first");
            return;
        }
        Optional<SensorGenerator.Config> cfgOpt =
                MessageDialogs.sensorGenerator(row.nodeId);
        if (cfgOpt.isEmpty()) return;
        SensorGenerator.Config cfg = cfgOpt.get();

        SensorGenerator sensor = new SensorGenerator(cfg, msg -> {
            try {
                tx.send(msg);
                Platform.runLater(() -> {
                    row.sent.set(row.sent.get() + 1);
                    if (row.sent.get() % 10 == 0) {
                        append(row, "⇒ sensor sent " + row.sent.get() + " status reports");
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> append(row,
                        "! sensor generator send failed: " + ex.getMessage()));
            }
        });
        sensorGenerators.put(row.name.get(), sensor);
        sensor.start();
        // Activity chip uses a distinct emoji so the operator can eyeball
        // WHICH generator is running when a row has both.
        String fovLabel = cfg.fovMode == SensorGenerator.FovMode.CONE ? "cone" : "polygon";
        row.activity.set(String.format("📡 sensor: %s FOV, %.0f°/s az, %.0f m range @ %d ms%s",
                fovLabel, cfg.azimuthRateDegPerSec, cfg.rangeMeters, cfg.tickMs,
                cfg.moving ? " (moving)" : ""));
        append(row, "📡 started sensor generator: " + fovLabel
                + " FOV, az " + cfg.initialAzimuthDeg + "° rotating "
                + cfg.azimuthRateDegPerSec + "°/s, range " + cfg.rangeMeters + " m"
                + (cfg.moving ? ", moving platform" : ", stationary"));
    }

    private void stopSensorGenerator(TxRow row) {
        SensorGenerator sensor = sensorGenerators.remove(row.name.get());
        if (sensor != null) {
            sensor.stop();
            // Only clear the activity chip if the detection generator is
            // ALSO not running — the chip is single-slot so we don't want to
            // wipe a still-running detection generator's label.
            if (!row.generating.get()) {
                row.activity.set("");
            }
            append(row, "■ stopped sensor generator");
        }
    }

    // ---------- Connect / disconnect ----------

    private void connect(TxRow row) {
        if (row == null || live.containsKey(row.name.get())) return;
        SapientMessageListener listener = new SapientMessageListener() {
            @Override
            public void onConnected(SocketAddress peer) {
                Platform.runLater(() -> {
                    row.status.set("connected");
                    append(row, "→ CONNECTED to " + peer);
                });
            }
            @Override
            public void onDisconnected(SocketAddress peer) {
                Platform.runLater(() -> {
                    row.status.set("disconnected");
                    append(row, "→ DISCONNECTED from " + peer);
                });
            }
            @Override
            public void onMessage(SocketAddress peer, SapientMessage msg) {
                Platform.runLater(() -> append(row,
                        "← REPLY " + msg.getContentCase().name() + " from " + msg.getNodeId()));
            }
            @Override
            public void onError(SocketAddress peer, Throwable cause) {
                Platform.runLater(() -> append(row, "! ERROR: " + cause));
            }
        };
        SapientTransmitter tx = new SapientTransmitter(
                row.name.get(), row.host.get(), row.port.get(), listener);
        new Thread(() -> {
            try {
                tx.connect();
                live.put(row.name.get(), tx);
            } catch (Exception ex) {
                Platform.runLater(() -> row.status.set("error: " + ex.getMessage()));
            }
        }, "connect-" + row.name.get()).start();
    }

    private void disconnect(TxRow row) {
        if (row == null) return;
        // Stop BOTH generator types first so we don't try to send on a dead
        // socket. Extended 2026-08-01 when sensor generator landed.
        stopGenerator(row);
        stopSensorGenerator(row);
        SapientTransmitter tx = live.remove(row.name.get());
        if (tx != null) new Thread(tx::close, "disconnect-" + row.name.get()).start();
    }

    /**
     * Wire a persistence callback. Called whenever a transmitter is added,
     * edited, or removed so the JSON session file mirrors the visible
     * table without waiting for window close.
     */
    public void setPersistNow(Runnable persistNow) {
        this.persistNow = (persistNow == null) ? () -> { } : persistNow;
    }

    /**
     * Open the edit dialog for a row, then — if the user pressed OK —
     * disconnect the transmitter (if live), apply the new values to the
     * row model, and persist. Matches SkyLord's spec: "saving stops the
     * interface, hit Connect when ready".
     *
     * <p>Row identity is keyed by name inside the {@code live} and
     * {@code generators} maps. If the user renamed the row, that's fine
     * — disconnect happens BEFORE the rename, so we look up the old name.
     */
    private void editRow(TxRow row) {
        if (row == null) return;
        boolean wasLive = live.containsKey(row.name.get());
        var editOpt = EditDialogs.editTransmitter(
                row.name.get(), row.host.get(), row.port.get(), row.nodeId, wasLive);
        if (editOpt.isEmpty()) return;
        var edit = editOpt.get();

        // Stop the generator + drop the socket BEFORE renaming so the old
        // name still resolves in the live/generators maps.
        stopGenerator(row);
        if (wasLive) disconnect(row);

        // Note: TxRow.nodeId is final — to persist a new UUID we swap the
        // row in-place with a fresh instance that carries the new node_id
        // (preserves position in the table + selection).
        int idx = rows.indexOf(row);
        TxRow replacement = new TxRow(edit.name, edit.host, edit.port, edit.nodeId);
        replacement.status.set("disconnected");
        if (idx >= 0) {
            rows.set(idx, replacement);
        } else {
            rows.add(replacement);
        }
        append(replacement, "✎ edited — name=" + edit.name + " host=" + edit.host
                + " port=" + edit.port + " nodeId=" + edit.nodeId
                + (wasLive ? " (was connected, now DISCONNECTED — press Connect when ready)" : ""));
        persistNow.run();
    }

    // ---------- Actions column: per-row connect / disconnect / remove ----------

    private Callback<TableColumn<TxRow, Void>, TableCell<TxRow, Void>>
            makeActionsCellFactory() {
        return col -> new TableCell<>() {
            private final Button connectBtn = Icons.iconButton(Feather.PLAY,
                    "Connect this transmitter");
            private final Button disconnectBtn = Icons.iconButton(Feather.SQUARE,
                    "Disconnect this transmitter");
            // 2026-08-01 (SkyLord): cog opens the full-property edit dialog.
            // Saving disconnects the transmitter if it was live — spec:
            // "saving stops the interface, hit Connect when ready". Sits
            // between disconnect and remove for reading-order symmetry.
            private final Button editBtn = Icons.iconButton(Feather.SETTINGS,
                    "Edit — saving disconnects this transmitter");
            private final Button removeBtn = Icons.dangerIconButton(Feather.TRASH_2,
                    "Remove this transmitter");
            private final HBox box;
            /** Row we're currently bound to, so we can unhook the listener on rebind. */
            private TxRow boundRow;
            /** Listener that repaints the Play button when the row's status flips. */
            private final javafx.beans.value.ChangeListener<String> statusListener =
                    (obs, oldV, newV) -> paintConnectButton(newV);

            {
                connectBtn.setFocusTraversable(false);
                disconnectBtn.setFocusTraversable(false);
                editBtn.setFocusTraversable(false);
                removeBtn.setFocusTraversable(false);
                connectBtn.getStyleClass().add("flat");
                disconnectBtn.getStyleClass().add("flat");
                editBtn.getStyleClass().add("flat");
                removeBtn.getStyleClass().add("flat");
                box = new HBox(4, connectBtn, disconnectBtn, editBtn, removeBtn);
                box.setAlignment(Pos.CENTER_LEFT);
                connectBtn.setOnAction(e -> {
                    TxRow r = getTableView().getItems().get(getIndex());
                    connect(r);
                });
                disconnectBtn.setOnAction(e -> {
                    TxRow r = getTableView().getItems().get(getIndex());
                    disconnect(r);
                });
                editBtn.setOnAction(e -> {
                    TxRow r = getTableView().getItems().get(getIndex());
                    editRow(r);
                });
                removeBtn.setOnAction(e -> {
                    TxRow r = getTableView().getItems().get(getIndex());
                    stopGenerator(r);
                    disconnect(r);
                    rows.remove(r);
                    persistNow.run();
                });
            }

            /**
             * Colour the Connect (play) button per current status:
             * <ul><li>connected → AtlantaFX 'success' style (green)</li>
             *     <li>anything else → AtlantaFX 'danger' style (red)</li></ul>
             * Semantic style classes, so colour follows the active theme.
             */
            private void paintConnectButton(String status) {
                connectBtn.getStyleClass().removeAll("success", "danger");
                if ("connected".equals(status)) {
                    connectBtn.getStyleClass().add("success");
                } else {
                    connectBtn.getStyleClass().add("danger");
                }
            }

            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (boundRow != null) {
                    boundRow.status.removeListener(statusListener);
                    boundRow = null;
                }
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                TxRow row = getTableView().getItems().get(getIndex());
                boundRow = row;
                row.status.addListener(statusListener);
                paintConnectButton(row.status.get());
                setGraphic(box);
            }
        };
    }

    /**
     * Cell factory for the Status column: colours the status text using the
     * active AtlantaFX theme's semantic colour variables. "connected" →
     * {@code -color-success-fg} (green), everything else →
     * {@code -color-danger-fg} (red).
     *
     * <p>AtlantaFX exposes {@code -color-success-*} and {@code -color-danger-*}
     * as CSS custom properties on the scene root, so binding
     * {@code -fx-text-fill} to them makes the shade automatically follow
     * whichever of the 7 themes (Primer / Nord / Cupertino Light+Dark /
     * Dracula) is active. We pin the colour via inline style rather than a
     * style class because JavaFX {@code TableCell} does NOT propagate the
     * {@code .success} / {@code .danger} style classes to its internal
     * text-fill (those classes are wired for buttons/tags, not cells) —
     * the label came out theme-default white in dark themes, which
     * SkyLord flagged 2026-08-01 11:59 UTC.
     */
    private Callback<TableColumn<TxRow, String>, TableCell<TxRow, String>>
            makeStatusCellFactory() {
        return col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(status);
                if ("connected".equals(status)) {
                    setStyle("-fx-text-fill: -color-success-fg; -fx-font-weight: bold;");
                } else {
                    setStyle("-fx-text-fill: -color-danger-fg; -fx-font-weight: bold;");
                }
            }
        };
    }

    // ---------- Custom activity cell (inline stop icon when generating) ----------

    private Callback<TableColumn<TxRow, String>, TableCell<TxRow, String>> makeActivityCellFactory() {
        return col -> new TableCell<>() {
            private final Button stopIcon;
            private final HBox box;
            private final Label label;
            {
                stopIcon = new Button();
                FontIcon i = new FontIcon(Feather.SQUARE);
                i.setIconSize(Icons.SIZE);
                stopIcon.setGraphic(i);
                stopIcon.getStyleClass().addAll("flat", "danger");
                stopIcon.setOnAction(e -> {
                    TxRow r = getTableView().getItems().get(getIndex());
                    stopGenerator(r);
                });
                label = new Label();
                box = new HBox(6, stopIcon, label);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(String activity, boolean empty) {
                super.updateItem(activity, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                TxRow row = getTableView().getItems().get(getIndex());
                if (row.generating.get()) {
                    label.setText(activity == null ? "" : activity);
                    setGraphic(box);
                } else {
                    setGraphic(null);
                }
            }
        };
    }

    // ---------- Stream ----------

    private void append(TxRow row, String line) {
        String ts = LocalTime.now().format(TS);
        String prefix = (row == null) ? "" : row.name.get() + ": ";
        stream.add(0, "[" + ts + "] " + prefix + line);
        while (stream.size() > 500) stream.remove(stream.size() - 1);
    }

    // ---------- Persistence ----------

    /** Snapshot the current configured-transmitter list for persistence. */
    public List<SessionStore.SavedTransmitter> snapshot() {
        List<SessionStore.SavedTransmitter> out = new ArrayList<>();
        for (TxRow r : rows) {
            out.add(new SessionStore.SavedTransmitter(
                    r.name.get(), r.host.get(), r.port.get(), r.nodeId));
        }
        return out;
    }

    /** Restore a previously-saved list of transmitters (comes up disconnected). */
    public void restore(List<SessionStore.SavedTransmitter> saved) {
        if (saved == null) return;
        for (SessionStore.SavedTransmitter s : saved) {
            if (s == null || s.name == null || s.name.isBlank()) continue;
            rows.add(new TxRow(s.name, s.host, s.port, s.nodeId));
        }
    }

    /** Stop every generator + disconnect every transmitter — called on window close. */
    public void shutdown() {
        for (DetectionGenerator gen : generators.values()) {
            try { gen.stop(); } catch (Exception ignored) { }
        }
        generators.clear();
        for (SensorGenerator sg : sensorGenerators.values()) {
            try { sg.stop(); } catch (Exception ignored) { }
        }
        sensorGenerators.clear();
        for (SapientTransmitter tx : live.values()) {
            try { tx.close(); } catch (Exception ignored) { }
        }
        live.clear();
    }

    // ---------- Row model ----------

    static final class TxRow {
        final SimpleStringProperty name;
        final SimpleStringProperty host;
        final SimpleIntegerProperty port;
        final SimpleStringProperty status;
        final SimpleIntegerProperty sent;
        final SimpleStringProperty activity;
        final SimpleBooleanProperty generating;
        /** Stable node UUID per transmitter row (used as SAPIENT node_id). */
        final String nodeId;

        TxRow(String name, String host, int port) {
            this(name, host, port, UUID.randomUUID().toString());
        }

        /** Constructor that preserves an existing node_id (used on restore). */
        TxRow(String name, String host, int port, String nodeId) {
            this.name = new SimpleStringProperty(name);
            this.host = new SimpleStringProperty(host);
            this.port = new SimpleIntegerProperty(port);
            this.status = new SimpleStringProperty("disconnected");
            this.sent = new SimpleIntegerProperty(0);
            this.activity = new SimpleStringProperty("");
            this.generating = new SimpleBooleanProperty(false);
            this.nodeId = (nodeId == null || nodeId.isBlank())
                    ? UUID.randomUUID().toString()
                    : nodeId;
        }
    }

    /** Also drop the old JSON template loader if the compiler still complains about the import. */
    @SuppressWarnings("unused")
    private static void unusedTemplateLoaderReference() {
        MessageTemplateLoader.class.getName();
    }
}
