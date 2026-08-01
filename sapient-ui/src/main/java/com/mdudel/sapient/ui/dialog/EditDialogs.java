/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.dialog;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.util.Optional;
import java.util.UUID;

/**
 * Modal dialogs for editing an existing receiver or transmitter's full
 * configuration. Every configurable property is editable in the dialog —
 * for receivers that's name + port; for transmitters that's name + host
 * + port + SAPIENT node UUID (with a "Regenerate" button that mints a
 * fresh v4 UUID if the user wants a new identity on the wire).
 *
 * <p>Saving does NOT touch the live socket — the caller is responsible for
 * stopping / disconnecting the interface first and then applying the new
 * values to the row model + persisting via {@code SessionStore.save(...)}.
 * This class is a pure presentation layer.
 */
public final class EditDialogs {

    private EditDialogs() {
        // utility
    }

    /** Immutable-ish carrier for the receiver edit result. */
    public static final class ReceiverEdit {
        public final String name;
        public final int port;
        public ReceiverEdit(String name, int port) {
            this.name = name;
            this.port = port;
        }
    }

    /** Immutable-ish carrier for the transmitter edit result. */
    public static final class TransmitterEdit {
        public final String name;
        public final String host;
        public final int port;
        public final String nodeId;
        public TransmitterEdit(String name, String host, int port, String nodeId) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.nodeId = nodeId;
        }
    }

    /**
     * Prompt the user to edit an existing receiver. Returns
     * {@link Optional#empty()} on Cancel / invalid input; otherwise the
     * new values (which may equal the originals if nothing changed).
     */
    public static Optional<ReceiverEdit> editReceiver(
            String currentName, int currentPort, boolean live) {

        Dialog<ReceiverEdit> d = new Dialog<>();
        d.setTitle("Edit receiver — " + currentName);
        d.setHeaderText(live
                ? "This receiver is currently running. Saving will stop it — press Start when you're ready."
                : "Edit any property; save when done.");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().setPadding(new Insets(4));

        TextField nameField = new TextField(currentName);
        nameField.setPromptText("Name (e.g. rx-A)");
        Spinner<Integer> portSpinner = new Spinner<>(new SpinnerValueFactory
                .IntegerSpinnerValueFactory(1, 65535, currentPort));
        portSpinner.setEditable(true);

        GridPane g = Forms.grid();
        Forms.addRow(g, 0, "Name",  nameField);
        Forms.addRow(g, 1, "Port",  portSpinner);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String name = nameField.getText().trim();
            if (name.isEmpty()) return null;   // caller shows a soft error via null == cancel
            int port = portSpinner.getValue() == null ? currentPort : portSpinner.getValue();
            return new ReceiverEdit(name, port);
        });
        return d.showAndWait().map(r -> r);   // preserve empty for cancel
    }

    /**
     * Prompt the user to edit an existing transmitter. All fields are
     * editable, including the SAPIENT node UUID (with a "Regenerate"
     * companion that mints a fresh v4 UUID on click — useful when the
     * operator wants the middleware to see this transmitter as a NEW
     * node on the next connect).
     */
    public static Optional<TransmitterEdit> editTransmitter(
            String currentName, String currentHost, int currentPort,
            String currentNodeId, boolean live) {

        Dialog<TransmitterEdit> d = new Dialog<>();
        d.setTitle("Edit transmitter — " + currentName);
        d.setHeaderText(live
                ? "This transmitter is currently connected. Saving will disconnect it — press Connect when you're ready."
                : "Edit any property; save when done.");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().setPadding(new Insets(4));

        TextField nameField = new TextField(currentName);
        nameField.setPromptText("Name (e.g. tx-to-dmm)");
        TextField hostField = new TextField(currentHost);
        hostField.setPromptText("Host (e.g. 10.0.0.20)");
        Spinner<Integer> portSpinner = new Spinner<>(new SpinnerValueFactory
                .IntegerSpinnerValueFactory(1, 65535, currentPort));
        portSpinner.setEditable(true);
        TextField nodeIdField = new TextField(currentNodeId == null ? "" : currentNodeId);
        nodeIdField.setPromptText("SAPIENT node UUID (v4)");
        javafx.scene.control.Button regen = new javafx.scene.control.Button("Regenerate");
        regen.setOnAction(e -> nodeIdField.setText(UUID.randomUUID().toString()));
        javafx.scene.layout.HBox nodeIdRow = new javafx.scene.layout.HBox(6, nodeIdField, regen);
        javafx.scene.layout.HBox.setHgrow(nodeIdField, javafx.scene.layout.Priority.ALWAYS);

        Label nodeIdHelp = new Label("Changing the UUID will make the middleware see this "
                + "transmitter as a new node on the next Connect.");
        nodeIdHelp.setWrapText(true);
        nodeIdHelp.getStyleClass().add("text-muted");

        GridPane g = Forms.grid();
        Forms.addRow(g, 0, "Name",     nameField);
        Forms.addRow(g, 1, "Host",     hostField);
        Forms.addRow(g, 2, "Port",     portSpinner);
        Forms.addRow(g, 3, "Node UUID", nodeIdRow);
        g.add(nodeIdHelp, 1, 4);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String name = nameField.getText().trim();
            String host = hostField.getText().trim();
            if (name.isEmpty() || host.isEmpty()) return null;
            int port = portSpinner.getValue() == null ? currentPort : portSpinner.getValue();
            String nodeId = nodeIdField.getText().trim();
            if (nodeId.isEmpty()) nodeId = UUID.randomUUID().toString();
            return new TransmitterEdit(name, host, port, nodeId);
        });
        return d.showAndWait().map(r -> r);
    }
}
