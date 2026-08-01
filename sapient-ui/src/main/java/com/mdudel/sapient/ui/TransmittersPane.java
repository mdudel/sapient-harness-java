/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.gen.DetectionGenerator;
import com.mdudel.sapient.core.template.MessageTemplateLoader;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientTransmitter;
import com.mdudel.sapient.ui.dialog.MessageDialogs;
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
import java.util.HashMap;
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
    private final ObservableList<String> stream = FXCollections.observableArrayList();

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
        Button connectBtn = Icons.iconButton(Feather.PLAY, "Connect selected transmitter");
        Button disconnectBtn = Icons.iconButton(Feather.SQUARE, "Disconnect selected transmitter");
        Button removeBtn = Icons.dangerIconButton(Feather.TRASH_2, "Remove selected transmitter");
        // Keep the table's selection intact when these buttons are clicked.
        addBtn.setFocusTraversable(false);
        connectBtn.setFocusTraversable(false);
        disconnectBtn.setFocusTraversable(false);
        removeBtn.setFocusTraversable(false);
        HBox form = new HBox(6, nameField, hostField, portField,
                addBtn, connectBtn, disconnectBtn, removeBtn);
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
        TableColumn<TxRow, Number> countCol = new TableColumn<>("Sent");
        countCol.setCellValueFactory(d -> d.getValue().sent);
        countCol.setPrefWidth(70);
        TableColumn<TxRow, String> activityCol = new TableColumn<>("Activity");
        // Bind to the activity STRING so the cell re-renders whenever activity
        // changes; the cell factory reads .generating for the stop-icon toggle.
        activityCol.setCellValueFactory(d -> d.getValue().activity);
        activityCol.setPrefWidth(240);
        activityCol.setCellFactory(makeActivityCellFactory());
        table.getColumns().addAll(nameCol, hostCol, portCol, statusCol, countCol, activityCol);
        table.setPrefHeight(200);

        VBox top = new VBox(new Label("Configured transmitters"), form, table);

        // --- Middle: message-type picker + send controls ---
        ChoiceBox<MsgType> typePicker = new ChoiceBox<>(FXCollections.observableArrayList(MsgType.values()));
        typePicker.setValue(MsgType.REGISTRATION);
        Button sendBtn = Icons.accentIconButton(Feather.SEND, "Configure and send the selected message type");
        Button quickRegBtn = Icons.iconButton(Feather.ZAP,
                "Quick send: minimal Registration with no dialog (smoke test)");
        Button stopGenBtn = Icons.dangerIconButton(Feather.SQUARE,
                "Stop the detection generator running on the selected transmitter");
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
        });

        connectBtn.setOnAction(e -> connect(table.getSelectionModel().getSelectedItem()));
        disconnectBtn.setOnAction(e -> disconnect(table.getSelectionModel().getSelectedItem()));
        removeBtn.setOnAction(e -> {
            TxRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) return;
            stopGenerator(row);
            disconnect(row);
            rows.remove(row);
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

        // Stop the detection generator on the selected transmitter (if any).
        stopGenBtn.setOnAction(e -> {
            TxRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) {
                append(null, "! no transmitter selected");
                return;
            }
            if (!row.generating.get()) {
                append(row, "! no detection generator is running on this transmitter");
                return;
            }
            stopGenerator(row);
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
        row.activity.set(String.format("▶ generating: %d tracks @ %d ms%s",
                cfg.trackCount, cfg.tickMs, cfg.moving ? " (moving)" : ""));
        append(row, "▶ started detection generator: " + cfg.trackCount
                + " tracks, " + cfg.tickMs + " ms rate, moving=" + cfg.moving);
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
        stopGenerator(row); // stop generator first so we don't try to send on a dead socket
        SapientTransmitter tx = live.remove(row.name.get());
        if (tx != null) new Thread(tx::close, "disconnect-" + row.name.get()).start();
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
            this.name = new SimpleStringProperty(name);
            this.host = new SimpleStringProperty(host);
            this.port = new SimpleIntegerProperty(port);
            this.status = new SimpleStringProperty("disconnected");
            this.sent = new SimpleIntegerProperty(0);
            this.activity = new SimpleStringProperty("");
            this.generating = new SimpleBooleanProperty(false);
            this.nodeId = UUID.randomUUID().toString();
        }
    }

    /** Also drop the old JSON template loader if the compiler still complains about the import. */
    @SuppressWarnings("unused")
    private static void unusedTemplateLoaderReference() {
        MessageTemplateLoader.class.getName();
    }
}
