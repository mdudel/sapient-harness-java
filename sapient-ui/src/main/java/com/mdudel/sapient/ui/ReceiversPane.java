/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.validation.SapientMessageValidator;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientReceiver;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * "Receivers" tab. Top: table of configured receivers (name, port, status).
 * Bottom: live message stream for the selected receiver.
 */
public final class ReceiversPane extends BorderPane {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final ObservableList<ReceiverRow> rows = FXCollections.observableArrayList();
    private final Map<String, SapientReceiver> live = new HashMap<>();
    private final ObservableList<String> stream = FXCollections.observableArrayList();

    public ReceiversPane() {
        setPadding(new Insets(8));

        // --- Top: form + table ---
        TextField nameField = new TextField();
        nameField.setPromptText("Name (e.g. rx-A)");
        TextField portField = new TextField();
        portField.setPromptText("Port (e.g. 12000)");
        Button addBtn = new Button("Add");
        Button startBtn = new Button("Start");
        Button stopBtn = new Button("Stop");
        Button removeBtn = new Button("Remove");
        HBox form = new HBox(6, nameField, portField, addBtn, startBtn, stopBtn, removeBtn);
        form.setAlignment(Pos.CENTER_LEFT);
        form.setPadding(new Insets(0, 0, 8, 0));

        TableView<ReceiverRow> table = new TableView<>(rows);
        TableColumn<ReceiverRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(d -> d.getValue().name);
        nameCol.setPrefWidth(140);
        TableColumn<ReceiverRow, Number> portCol = new TableColumn<>("Port");
        portCol.setCellValueFactory(d -> d.getValue().port);
        portCol.setPrefWidth(80);
        TableColumn<ReceiverRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(d -> d.getValue().status);
        statusCol.setPrefWidth(120);
        TableColumn<ReceiverRow, Number> countCol = new TableColumn<>("Received");
        countCol.setCellValueFactory(d -> d.getValue().received);
        countCol.setPrefWidth(100);
        table.getColumns().addAll(nameCol, portCol, statusCol, countCol);
        table.setPrefHeight(220);

        VBox top = new VBox(form, table);

        // --- Bottom: message stream ---
        ListView<String> streamView = new ListView<>(stream);
        streamView.setPrefHeight(320);

        setTop(top);
        setCenter(streamView);

        // --- Wire up buttons ---
        addBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            String portText = portField.getText().trim();
            if (name.isEmpty() || portText.isEmpty()) return;
            int port;
            try { port = Integer.parseInt(portText); }
            catch (NumberFormatException ex) { return; }
            rows.add(new ReceiverRow(name, port));
            nameField.clear();
            portField.clear();
        });

        startBtn.setOnAction(e -> {
            ReceiverRow row = table.getSelectionModel().getSelectedItem();
            if (row == null || live.containsKey(row.name.get())) return;
            SapientMessageListener listener = new SapientMessageListener() {
                @Override
                public void onConnected(SocketAddress peer) {
                    Platform.runLater(() -> append(row, "← CONNECT " + peer));
                }
                @Override
                public void onDisconnected(SocketAddress peer) {
                    Platform.runLater(() -> append(row, "← DISCONNECT " + peer));
                }
                @Override
                public void onMessage(SocketAddress peer, SapientMessage msg) {
                    SapientMessageValidator.ValidationResult v =
                            SapientMessageValidator.validate(msg);
                    Platform.runLater(() -> {
                        row.received.set(row.received.get() + 1);
                        append(row, "← " + peer + "   " + v.summary());
                    });
                }
                @Override
                public void onError(SocketAddress peer, Throwable cause) {
                    Platform.runLater(() -> append(row, "! ERROR from " + peer + ": " + cause));
                }
            };
            SapientReceiver rx = new SapientReceiver(row.name.get(), row.port.get(), listener);
            new Thread(() -> {
                try {
                    rx.start();
                    live.put(row.name.get(), rx);
                    Platform.runLater(() -> row.status.set("running"));
                } catch (Exception ex) {
                    Platform.runLater(() -> row.status.set("error: " + ex.getMessage()));
                }
            }, "start-" + row.name.get()).start();
        });

        stopBtn.setOnAction(e -> {
            ReceiverRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) return;
            SapientReceiver rx = live.remove(row.name.get());
            if (rx != null) {
                new Thread(() -> {
                    rx.stop();
                    Platform.runLater(() -> row.status.set("stopped"));
                }, "stop-" + row.name.get()).start();
            }
        });

        removeBtn.setOnAction(e -> {
            ReceiverRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) return;
            SapientReceiver rx = live.remove(row.name.get());
            if (rx != null) rx.stop();
            rows.remove(row);
        });
    }

    private void append(ReceiverRow row, String line) {
        String ts = LocalTime.now().format(TS);
        stream.add(0, "[" + ts + "] " + row.name.get() + ": " + line);
        // cap history
        while (stream.size() > 500) stream.remove(stream.size() - 1);
    }

    /** Row model for a configured receiver (name, port, status, received count). */
    static final class ReceiverRow {
        final javafx.beans.property.SimpleStringProperty name;
        final javafx.beans.property.SimpleIntegerProperty port;
        final javafx.beans.property.SimpleStringProperty status;
        final javafx.beans.property.SimpleIntegerProperty received;

        ReceiverRow(String name, int port) {
            this.name = new javafx.beans.property.SimpleStringProperty(name);
            this.port = new javafx.beans.property.SimpleIntegerProperty(port);
            this.status = new javafx.beans.property.SimpleStringProperty("stopped");
            this.received = new javafx.beans.property.SimpleIntegerProperty(0);
        }
    }
}
