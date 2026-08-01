/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.validation.SapientMessageValidator;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientReceiver;
import com.mdudel.sapient.ui.persist.SessionStore;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
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
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        Button addBtn = Icons.accentIconButton(Feather.PLUS, "Add receiver");
        addBtn.setFocusTraversable(false);
        HBox form = new HBox(6, nameField, portField, addBtn);
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
        TableColumn<ReceiverRow, Void> actionsCol = new TableColumn<>("Actions");
        actionsCol.setCellFactory(makeActionsCellFactory());
        actionsCol.setPrefWidth(140);
        actionsCol.setSortable(false);
        table.getColumns().addAll(nameCol, portCol, statusCol, countCol, actionsCol);
        table.setPrefHeight(220);

        VBox top = new VBox(new Label("Configured receivers"), form, table);

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
            ReceiverRow newRow = new ReceiverRow(name, port);
            rows.add(newRow);
            table.getSelectionModel().select(newRow);   // <-- auto-select so the next action lands
            nameField.clear();
            portField.clear();
        });

    }

    // ---------- Per-row actions ----------

    private void startRow(ReceiverRow row) {
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
    }

    private void stopRow(ReceiverRow row) {
        if (row == null) return;
        SapientReceiver rx = live.remove(row.name.get());
        if (rx != null) {
            new Thread(() -> {
                rx.stop();
                Platform.runLater(() -> row.status.set("stopped"));
            }, "stop-" + row.name.get()).start();
        }
    }

    private void removeRow(ReceiverRow row) {
        if (row == null) return;
        SapientReceiver rx = live.remove(row.name.get());
        if (rx != null) rx.stop();
        rows.remove(row);
    }

    // ---------- Actions column cell factory ----------

    private Callback<TableColumn<ReceiverRow, Void>, TableCell<ReceiverRow, Void>>
            makeActionsCellFactory() {
        return col -> new TableCell<>() {
            private final Button startBtn = Icons.iconButton(Feather.PLAY, "Start this receiver");
            private final Button stopBtn  = Icons.iconButton(Feather.SQUARE, "Stop this receiver");
            private final Button removeBtn = Icons.dangerIconButton(Feather.TRASH_2, "Remove this receiver");
            private final HBox box;
            /** Row we're currently bound to, so we can unhook the listener on rebind. */
            private ReceiverRow boundRow;
            /** Listener that repaints the Play button when the row's status flips. */
            private final javafx.beans.value.ChangeListener<String> statusListener =
                    (obs, oldV, newV) -> paintStartButton(newV);

            {
                startBtn.setFocusTraversable(false);
                stopBtn.setFocusTraversable(false);
                removeBtn.setFocusTraversable(false);
                startBtn.getStyleClass().add("flat");
                stopBtn.getStyleClass().add("flat");
                removeBtn.getStyleClass().add("flat");
                box = new HBox(4, startBtn, stopBtn, removeBtn);
                box.setAlignment(Pos.CENTER_LEFT);
                startBtn.setOnAction(e -> {
                    ReceiverRow r = getTableView().getItems().get(getIndex());
                    startRow(r);
                });
                stopBtn.setOnAction(e -> {
                    ReceiverRow r = getTableView().getItems().get(getIndex());
                    stopRow(r);
                });
                removeBtn.setOnAction(e -> {
                    ReceiverRow r = getTableView().getItems().get(getIndex());
                    removeRow(r);
                });
            }

            /**
             * Colour the Play (start) button per current status:
             * <ul><li>running → AtlantaFX 'success' style (green)</li>
             *     <li>anything else → AtlantaFX 'danger' style (red)</li></ul>
             * These are semantic style classes, so the actual colour follows
             * whichever theme is active.
             */
            private void paintStartButton(String status) {
                startBtn.getStyleClass().removeAll("success", "danger");
                if ("running".equals(status)) {
                    startBtn.getStyleClass().add("success");
                } else {
                    startBtn.getStyleClass().add("danger");
                }
            }

            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                // Unhook from any previously-bound row's status listener.
                if (boundRow != null) {
                    boundRow.status.removeListener(statusListener);
                    boundRow = null;
                }
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                ReceiverRow row = getTableView().getItems().get(getIndex());
                boundRow = row;
                row.status.addListener(statusListener);
                paintStartButton(row.status.get());
                setGraphic(box);
            }
        };
    }

    private void append(ReceiverRow row, String line) {
        String ts = LocalTime.now().format(TS);
        stream.add(0, "[" + ts + "] " + row.name.get() + ": " + line);
        // cap history
        while (stream.size() > 500) stream.remove(stream.size() - 1);
    }

    /** Snapshot the current configured-receiver list for persistence. */
    public List<SessionStore.SavedReceiver> snapshot() {
        List<SessionStore.SavedReceiver> out = new ArrayList<>();
        for (ReceiverRow r : rows) {
            out.add(new SessionStore.SavedReceiver(r.name.get(), r.port.get()));
        }
        return out;
    }

    /** Restore a previously-saved list of receivers (comes up stopped). */
    public void restore(List<SessionStore.SavedReceiver> saved) {
        if (saved == null) return;
        for (SessionStore.SavedReceiver s : saved) {
            if (s == null || s.name == null || s.name.isBlank()) continue;
            rows.add(new ReceiverRow(s.name, s.port));
        }
    }

    /** Stop every running receiver — called on window close. */
    public void shutdown() {
        for (SapientReceiver rx : live.values()) {
            try { rx.stop(); } catch (Exception ignored) { }
        }
        live.clear();
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
