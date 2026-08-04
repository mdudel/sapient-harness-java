/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.dialog;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
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
        public final boolean enforceHandshake;
        public ReceiverEdit(String name, int port) {
            this(name, port, false);
        }
        public ReceiverEdit(String name, int port, boolean enforceHandshake) {
            this.name = name;
            this.port = port;
            this.enforceHandshake = enforceHandshake;
        }
    }

    /** Immutable-ish carrier for the transmitter edit result. */
    public static final class TransmitterEdit {
        public final String name;
        public final String host;
        public final int port;
        public final String nodeId;
        public final boolean enforceHandshake;
        public TransmitterEdit(String name, String host, int port, String nodeId) {
            this(name, host, port, nodeId, false);
        }
        public TransmitterEdit(String name, String host, int port, String nodeId, boolean enforceHandshake) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.nodeId = nodeId;
            this.enforceHandshake = enforceHandshake;
        }
    }

    /**
     * Prompt the user to edit an existing receiver. Returns
     * {@link Optional#empty()} on Cancel / invalid input; otherwise the
     * new values (which may equal the originals if nothing changed).
     */
    public static Optional<ReceiverEdit> editReceiver(
            String currentName, int currentPort,
            boolean currentEnforce, boolean live) {

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
        CheckBox enforceBox = new CheckBox("Enforce SAPIENT handshake");
        enforceBox.setSelected(currentEnforce);
        Label enforceHelp = new Label("When on, the receiver requires each connecting peer to send "
                + "Registration first (BSI Flex 335 v2.0 §6.2.2), auto-replies with a valid "
                + "RegistrationAck, refuses out-of-order traffic, and closes peers that miss "
                + "3× their declared status_interval (§6.3.2.1).");
        enforceHelp.setWrapText(true);
        enforceHelp.getStyleClass().add("text-muted");

        GridPane g = Forms.grid();
        Forms.addRow(g, 0, "Name",  nameField);
        Forms.addRow(g, 1, "Port",  portSpinner);
        Forms.addRow(g, 2, "Mode",  enforceBox);
        g.add(enforceHelp, 1, 3);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String name = nameField.getText().trim();
            if (name.isEmpty()) return null;   // caller shows a soft error via null == cancel
            int port = portSpinner.getValue() == null ? currentPort : portSpinner.getValue();
            return new ReceiverEdit(name, port, enforceBox.isSelected());
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
            String currentNodeId, boolean currentEnforce, boolean live) {

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

        CheckBox enforceBox = new CheckBox("Enforce SAPIENT handshake");
        enforceBox.setSelected(currentEnforce);
        Label enforceHelp = new Label("When on, the transmitter drives the full BSI Flex 335 v2.0 "
                + "handshake on Connect: sends Registration first, waits for RegistrationAck, then "
                + "emits the initial StatusReport (§6.3.1) and schedules heartbeats at the declared "
                + "status_interval (§6.3.2). Manual Send is gated by the handshake state — you can't "
                + "send StatusReport / DetectionReport / etc. until REGISTERED. Reconnects follow the "
                + "10 s retry / 2 min re-register rule (§4.9). Sends SYSTEM_GOODBYE on Disconnect (§4.8).");
        enforceHelp.setWrapText(true);
        enforceHelp.getStyleClass().add("text-muted");

        GridPane g = Forms.grid();
        Forms.addRow(g, 0, "Name",     nameField);
        Forms.addRow(g, 1, "Host",     hostField);
        Forms.addRow(g, 2, "Port",     portSpinner);
        Forms.addRow(g, 3, "Node UUID", nodeIdRow);
        g.add(nodeIdHelp, 1, 4);
        Forms.addRow(g, 5, "Mode", enforceBox);
        g.add(enforceHelp, 1, 6);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String name = nameField.getText().trim();
            String host = hostField.getText().trim();
            if (name.isEmpty() || host.isEmpty()) return null;
            int port = portSpinner.getValue() == null ? currentPort : portSpinner.getValue();
            String nodeId = nodeIdField.getText().trim();
            if (nodeId.isEmpty()) nodeId = UUID.randomUUID().toString();
            return new TransmitterEdit(name, host, port, nodeId, enforceBox.isSelected());
        });
        return d.showAndWait().map(r -> r);
    }
}
