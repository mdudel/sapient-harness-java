/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.google.protobuf.Timestamp;
import com.mdudel.sapient.core.template.MessageTemplateLoader;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientTransmitter;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Transmitters" tab. Top: table of configured transmitters (name, host, port, status).
 * Middle: JSON template editor. Bottom: send controls + live event stream.
 */
public final class TransmittersPane extends BorderPane {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final ObservableList<TxRow> rows = FXCollections.observableArrayList();
    private final Map<String, SapientTransmitter> live = new HashMap<>();
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
        Button connectBtn = Icons.iconButton(Feather.LINK, "Connect selected transmitter");
        Button disconnectBtn = Icons.iconButton(Feather.LINK_2, "Disconnect selected transmitter");
        Button removeBtn = Icons.dangerIconButton(Feather.TRASH_2, "Remove selected transmitter");
        HBox form = new HBox(6, nameField, hostField, portField,
                addBtn, connectBtn, disconnectBtn, removeBtn);
        form.setAlignment(Pos.CENTER_LEFT);
        form.setPadding(new Insets(0, 0, 8, 0));

        TableView<TxRow> table = new TableView<>(rows);
        TableColumn<TxRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(d -> d.getValue().name);
        nameCol.setPrefWidth(140);
        TableColumn<TxRow, String> hostCol = new TableColumn<>("Host");
        hostCol.setCellValueFactory(d -> d.getValue().host);
        hostCol.setPrefWidth(140);
        TableColumn<TxRow, Number> portCol = new TableColumn<>("Port");
        portCol.setCellValueFactory(d -> d.getValue().port);
        portCol.setPrefWidth(80);
        TableColumn<TxRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(d -> d.getValue().status);
        statusCol.setPrefWidth(120);
        TableColumn<TxRow, Number> countCol = new TableColumn<>("Sent");
        countCol.setCellValueFactory(d -> d.getValue().sent);
        countCol.setPrefWidth(80);
        table.getColumns().addAll(nameCol, hostCol, portCol, statusCol, countCol);
        table.setPrefHeight(180);

        VBox top = new VBox(new Label("Configured transmitters"), form, table);

        // --- Middle: JSON template editor ---
        TextArea templateArea = new TextArea(defaultRegistrationJson());
        templateArea.setPrefRowCount(10);
        Button sendOnceBtn = Icons.accentIconButton(Feather.SEND, "Send once (to selected transmitter)");
        Button synthBtn = Icons.iconButton(Feather.REFRESH_CW, "Fill with a fresh synthesised Registration");
        HBox sendRow = new HBox(6, new Label("Template:"), sendOnceBtn, synthBtn);
        sendRow.setAlignment(Pos.CENTER_LEFT);
        sendRow.setPadding(new Insets(6, 0, 6, 0));

        VBox middle = new VBox(templateArea, sendRow);

        // --- Bottom: event stream ---
        ListView<String> streamView = new ListView<>(stream);
        streamView.setPrefHeight(200);

        setTop(top);
        setCenter(middle);
        setBottom(streamView);

        // --- Wire up buttons ---
        addBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            String host = hostField.getText().trim();
            String portText = portField.getText().trim();
            if (name.isEmpty() || host.isEmpty() || portText.isEmpty()) return;
            int port;
            try { port = Integer.parseInt(portText); }
            catch (NumberFormatException ex) { return; }
            rows.add(new TxRow(name, host, port));
            nameField.clear();
            hostField.clear();
            portField.clear();
        });

        connectBtn.setOnAction(e -> {
            TxRow row = table.getSelectionModel().getSelectedItem();
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
        });

        disconnectBtn.setOnAction(e -> {
            TxRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) return;
            SapientTransmitter tx = live.remove(row.name.get());
            if (tx != null) {
                new Thread(tx::close, "disconnect-" + row.name.get()).start();
            }
        });

        removeBtn.setOnAction(e -> {
            TxRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) return;
            SapientTransmitter tx = live.remove(row.name.get());
            if (tx != null) tx.close();
            rows.remove(row);
        });

        sendOnceBtn.setOnAction(e -> {
            TxRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) {
                append(null, "! no transmitter selected");
                return;
            }
            SapientTransmitter tx = live.get(row.name.get());
            if (tx == null || !tx.isConnected()) {
                append(row, "! not connected");
                return;
            }
            try {
                SapientMessage msg = MessageTemplateLoader.fromJson(templateArea.getText());
                tx.send(msg);
                row.sent.set(row.sent.get() + 1);
                append(row, "→ SENT " + msg.getContentCase().name());
            } catch (Exception ex) {
                append(row, "! JSON parse failure: " + ex.getMessage());
            }
        });

        synthBtn.setOnAction(e -> templateArea.setText(defaultRegistrationJson()));
    }

    private void append(TxRow row, String line) {
        String ts = LocalTime.now().format(TS);
        String prefix = (row == null) ? "" : row.name.get() + ": ";
        stream.add(0, "[" + ts + "] " + prefix + line);
        while (stream.size() > 500) stream.remove(stream.size() - 1);
    }

    /** Default JSON template — a minimal legal Registration. */
    private static String defaultRegistrationJson() {
        try {
            SapientMessage msg = SapientMessage.newBuilder()
                    .setTimestamp(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond())
                            .build())
                    .setNodeId(UUID.randomUUID().toString())
                    .setRegistration(Registration.newBuilder().build())
                    .build();
            return MessageTemplateLoader.toJson(msg);
        } catch (Exception e) {
            return "{ /* failed to synthesise default: " + e.getMessage() + " */ }";
        }
    }

    static final class TxRow {
        final javafx.beans.property.SimpleStringProperty name;
        final javafx.beans.property.SimpleStringProperty host;
        final javafx.beans.property.SimpleIntegerProperty port;
        final javafx.beans.property.SimpleStringProperty status;
        final javafx.beans.property.SimpleIntegerProperty sent;

        TxRow(String name, String host, int port) {
            this.name = new javafx.beans.property.SimpleStringProperty(name);
            this.host = new javafx.beans.property.SimpleStringProperty(host);
            this.port = new javafx.beans.property.SimpleIntegerProperty(port);
            this.status = new javafx.beans.property.SimpleStringProperty("disconnected");
            this.sent = new javafx.beans.property.SimpleIntegerProperty(0);
        }
    }
}
