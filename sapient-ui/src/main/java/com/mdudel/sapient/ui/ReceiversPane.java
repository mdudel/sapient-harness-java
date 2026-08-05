/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.validation.SapientMessageValidator;
import com.mdudel.sapient.net.RegistrationPolicySpec;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientReceiver;
import com.mdudel.sapient.ui.dialog.EditDialogs;
import com.mdudel.sapient.ui.dialog.PolicyDialog;
import com.mdudel.sapient.ui.persist.SessionStore;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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

import java.util.UUID;

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

    /**
     * Optional persistence hook: invoked whenever the receiver list changes
     * shape or a row is edited, so the JSON session file stays current
     * without waiting for window close. Wired by {@code SapientHarnessApp}.
     * Null-safe — tests / headless bring-up can leave this unset.
     */
    private Runnable persistNow = () -> { };

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
        // 2026-08-04: column layout restructure per Marty's UI ask.
        // Actions (start/stop) moves to the FRONT for immediate reach;
        // the old right-hand tail collapses into a single Config column
        // holding cog + delete + shield (strict-mode only) so nothing
        // gets clipped when the table narrows.
        TableColumn<ReceiverRow, Void> actionsCol = new TableColumn<>("Actions");
        actionsCol.setCellFactory(makeActionsCellFactory());
        actionsCol.setPrefWidth(90);
        actionsCol.setSortable(false);
        TableColumn<ReceiverRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(d -> d.getValue().name);
        nameCol.setPrefWidth(140);
        TableColumn<ReceiverRow, Number> portCol = new TableColumn<>("Port");
        portCol.setCellValueFactory(d -> d.getValue().port);
        portCol.setPrefWidth(80);
        TableColumn<ReceiverRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(d -> d.getValue().status);
        statusCol.setPrefWidth(120);
        // 2026-08-01 (SkyLord): status text is red when the receiver is
        // NOT running and green when it IS. Uses AtlantaFX 'success' /
        // 'danger' style classes on the Label so the actual shade follows
        // whichever theme (Primer / Nord / Cupertino / Dracula ...) is
        // active — harmonious in every palette.
        statusCol.setCellFactory(makeStatusCellFactory());
        TableColumn<ReceiverRow, Number> countCol = new TableColumn<>("Received");
        countCol.setCellValueFactory(d -> d.getValue().received);
        countCol.setPrefWidth(100);
        TableColumn<ReceiverRow, Boolean> enforceCol = new TableColumn<>("Enforce handshake");
        enforceCol.setCellValueFactory(d -> d.getValue().enforceHandshake);
        enforceCol.setCellFactory(makeEnforceCellFactory());
        // 170 not 140 — the shorter width truncated the header to
        // 'Enforce hand…' in the 1600 px screenshot on 2026-08-04.
        enforceCol.setPrefWidth(170);
        enforceCol.setSortable(false);
        TableColumn<ReceiverRow, Void> configCol = new TableColumn<>("Config");
        configCol.setCellFactory(makeConfigCellFactory());
        configCol.setPrefWidth(130);
        configCol.setSortable(false);
        table.getColumns().addAll(actionsCol, nameCol, portCol, statusCol, countCol,
                enforceCol, configCol);
        table.setPrefHeight(220);
        // CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS: distributes any leftover
        // horizontal space across all columns proportionally, so we don't
        // get a phantom empty trailing column (JavaFX's default filler)
        // and columns adapt if the operator resizes the window.
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        // Inline validation hint: shown in red under the form when the user
        // clicks Add with empty or invalid fields. Blank while the fields
        // are being edited so it doesn't nag mid-type. Fix 2026-08-04 for
        // Marty's 12:01 UTC report -- previous silent-return-on-empty made
        // the Add button feel dead when a user forgot a field.
        Label formHint = new Label("");
        formHint.setStyle("-fx-text-fill: -color-danger-fg;");
        formHint.setWrapText(true);
        // Clear hint as soon as the user starts editing either field again.
        nameField.textProperty().addListener((o, a, b) -> formHint.setText(""));
        portField.textProperty().addListener((o, a, b) -> formHint.setText(""));

        VBox top = new VBox(new Label("Configured receivers"), form, formHint, table);

        // --- Bottom: message stream ---
        //
        // Header row: "Message stream" label + Clear button. Sibling parity
        // with TransmittersPane (2026-08-05, Marty's ask). Clear wipes the
        // observable list backing the ListView; the 500-line cap in
        // appendStream(...) still applies to new messages.
        ListView<String> streamView = new ListView<>(stream);
        streamView.setPrefHeight(320);
        // Tighten row height + kill AtlantaFX's default vertical cell
        // padding so log lines aren't spaced out. Inline stylesheet ->
        // scoped to THIS ListView only. See TransmittersPane for the
        // rationale (Marty 2026-08-05 whitespace complaint).
        streamView.setStyle(
                "-fx-cell-size: 20px;");
        streamView.getStylesheets().add(
                "data:text/css," + java.net.URLEncoder.encode(
                        ".list-cell { -fx-padding: 1 6 1 6; }",
                        java.nio.charset.StandardCharsets.UTF_8));
        Button clearStreamBtn = Icons.dangerIconButton(Feather.TRASH_2,
                "Clear the message stream");
        clearStreamBtn.setFocusTraversable(false);
        clearStreamBtn.setOnAction(e -> stream.clear());
        HBox streamHeader = new HBox(8,
                new Label("Message stream"),
                new javafx.scene.layout.Region() {{
                    javafx.scene.layout.HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS);
                }},
                clearStreamBtn);
        streamHeader.setAlignment(Pos.CENTER_LEFT);
        streamHeader.setPadding(new Insets(4, 4, 4, 4));
        VBox streamBox = new VBox(streamHeader, streamView);
        VBox.setVgrow(streamView, javafx.scene.layout.Priority.ALWAYS);

        setTop(top);
        setCenter(streamBox);

        // --- Wire up buttons ---
        addBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            String portText = portField.getText().trim();
            if (name.isEmpty() && portText.isEmpty()) {
                formHint.setText("Enter a name and port before clicking Add.");
                return;
            }
            if (name.isEmpty()) {
                formHint.setText("Name is required.");
                return;
            }
            if (portText.isEmpty()) {
                formHint.setText("Port is required.");
                return;
            }
            int port;
            try { port = Integer.parseInt(portText); }
            catch (NumberFormatException ex) {
                formHint.setText("Port must be an integer (1-65535), got '" + portText + "'.");
                return;
            }
            if (port < 1 || port > 65535) {
                formHint.setText("Port must be between 1 and 65535, got " + port + ".");
                return;
            }
            // Guard against duplicate names -- SapientReceiver is keyed on name
            // and a duplicate would clash silently in the live/generators maps.
            for (ReceiverRow existing : rows) {
                if (existing.name.get().equals(name)) {
                    formHint.setText("A receiver named '" + name + "' already exists.");
                    return;
                }
            }
            ReceiverRow newRow = new ReceiverRow(name, port);
            rows.add(newRow);
            table.getSelectionModel().select(newRow);   // <-- auto-select so the next action lands
            nameField.clear();
            portField.clear();
            formHint.setText("");
            persistNow.run();
        });

    }

    /**
     * Wire a persistence callback. Called whenever a receiver is added,
     * edited, or removed so the JSON session file mirrors the visible
     * table without waiting for window close.
     */
    public void setPersistNow(Runnable persistNow) {
        this.persistNow = (persistNow == null) ? () -> { } : persistNow;
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
        boolean enforce = row.enforceHandshake.get();
        SapientReceiver rx;
        if (enforce) {
            if (row.selfNodeId == null) row.selfNodeId = UUID.randomUUID().toString();
            SapientReceiver.Builder builder = SapientReceiver.builder(row.name.get(), row.port.get())
                    .listener(listener)
                    .enforceHandshake(true)
                    .selfNodeId(row.selfNodeId);
            if (row.policySpec != null && !row.policySpec.isPermissive()) {
                builder.registrationPolicySpec(row.policySpec);
                append(row, "🛡 policy: " + describePolicy(row.policySpec));
            }
            rx = builder.build();
            append(row, "★ STRICT mode — fusion nodeId=" + row.selfNodeId
                    + " (BSI Flex 335 v2.0 handshake enforced)");
        } else {
            rx = new SapientReceiver(row.name.get(), row.port.get(), listener);
        }
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
        persistNow.run();
    }

    /**
     * Open the edit dialog for a row, then — if the user pressed OK — stop
     * the receiver (if running), apply the new values, and persist. Matches
     * SkyLord's spec: "saving stops the interface, hit Start when ready".
     */
    private void editRow(ReceiverRow row) {
        if (row == null) return;
        boolean wasLive = live.containsKey(row.name.get());
        var editOpt = EditDialogs.editReceiver(row.name.get(), row.port.get(),
                row.enforceHandshake.get(), wasLive);
        if (editOpt.isEmpty()) return;
        var edit = editOpt.get();

        // Stop first so the socket is closed before we rename / re-port the row.
        if (wasLive) stopRow(row);

        // Apply the new values to the row model. Property change fires the
        // cell listeners so the Name / Port / Status cells repaint.
        row.name.set(edit.name);
        row.port.set(edit.port);
        row.enforceHandshake.set(edit.enforceHandshake);
        // Status stays whatever stopRow set it to (typically "stopped").
        append(row, "✎ edited — name=" + edit.name + " port=" + edit.port
                + " enforceHandshake=" + edit.enforceHandshake
                + (wasLive ? " (was running, now STOPPED — press Start when ready)" : ""));
        persistNow.run();
    }

    /**
     * Open the {@link PolicyDialog} for a row. Available only when the row
     * has {@code enforceHandshake=true} — dumb-mode receivers accept
     * everything by definition. If the row is running, stops it first
     * (same UX pattern as editing the port).
     */
    private void editPolicy(ReceiverRow row) {
        if (row == null) return;
        if (!row.enforceHandshake.get()) {
            append(row, "! policy editor available only in strict mode — tick Enforce handshake first");
            return;
        }
        boolean wasLive = live.containsKey(row.name.get());
        if (wasLive) stopRow(row);
        var out = PolicyDialog.edit(row.name.get(), row.policySpec);
        if (out.isEmpty()) return;
        row.policySpec = out.get();
        append(row, "🛡 policy updated: " + describePolicy(row.policySpec)
                + (wasLive ? " (was running, now STOPPED — press Start when ready)" : ""));
        persistNow.run();
    }

    /** Short one-line summary of a policy spec for log messages. */
    private static String describePolicy(RegistrationPolicySpec s) {
        if (s == null || s.isPermissive()) return "accept all";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (s.requiredIcdVersion != null && !s.requiredIcdVersion.isBlank()) {
            parts.add("icd='" + s.requiredIcdVersion + "'");
        }
        if (s.allowedNodeTypes != null && !s.allowedNodeTypes.isEmpty()) {
            parts.add("types=" + s.allowedNodeTypes);
        }
        if (s.allowedNodeIds != null && !s.allowedNodeIds.isEmpty()) {
            parts.add("allow=" + s.allowedNodeIds.size() + " id(s)");
        }
        if (s.deniedNodeIds != null && !s.deniedNodeIds.isEmpty()) {
            parts.add("deny=" + s.deniedNodeIds.size() + " id(s)");
        }
        return String.join(", ", parts);
    }

    // ---------- Actions column cell factory ----------

    /**
     * Cell factory for the leading "Actions" column: just Start + Stop.
     * Moved to the front of the row 2026-08-04 so the icons are always
     * reachable regardless of how narrow the table gets. Config cog +
     * delete + shield live in the trailing "Config" column now.
     */
    private Callback<TableColumn<ReceiverRow, Void>, TableCell<ReceiverRow, Void>>
            makeActionsCellFactory() {
        return col -> new TableCell<>() {
            private final Button startBtn = Icons.iconButton(Feather.PLAY, "Start this receiver");
            private final Button stopBtn  = Icons.iconButton(Feather.SQUARE, "Stop this receiver");
            private final HBox box;
            /** Row we're currently bound to, so we can unhook the listener on rebind. */
            private ReceiverRow boundRow;
            /** Listener that repaints the Play button when the row's status flips. */
            private final javafx.beans.value.ChangeListener<String> statusListener =
                    (obs, oldV, newV) -> paintStartButton(newV);

            {
                startBtn.setFocusTraversable(false);
                stopBtn.setFocusTraversable(false);
                startBtn.getStyleClass().add("flat");
                stopBtn.getStyleClass().add("flat");
                box = new HBox(4, startBtn, stopBtn);
                box.setAlignment(Pos.CENTER_LEFT);
                startBtn.setOnAction(e -> {
                    ReceiverRow r = getTableView().getItems().get(getIndex());
                    startRow(r);
                });
                stopBtn.setOnAction(e -> {
                    ReceiverRow r = getTableView().getItems().get(getIndex());
                    stopRow(r);
                });
            }

            /**
             * Colour the Play (start) button per current status:
             * <ul><li>running → AtlantaFX 'success' style (green)</li>
             *     <li>anything else → AtlantaFX 'danger' style (red)</li></ul>
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

    /**
     * Cell factory for the trailing "Config" column: cog + delete + shield.
     * The shield (RegistrationPolicy editor, Phase 5) is visible only when
     * the row has {@code enforceHandshake=true}. All three buttons use the
     * same flat icon-button style as the leading Actions column so they
     * look uniform across the row.
     *
     * <p>Merged 2026-08-04 from the old "Actions" tail column that used
     * to hold start/stop/cog/delete/shield — the cluster was too wide to
     * fit reliably. Splitting into leading Actions (start/stop) + trailing
     * Config (cog/delete/shield) keeps every icon on-screen.
     */
    private Callback<TableColumn<ReceiverRow, Void>, TableCell<ReceiverRow, Void>>
            makeConfigCellFactory() {
        return col -> new TableCell<>() {
            // Cog opens the full-property edit dialog. Saving stops the
            // receiver if it was running — same contract as the transmitter.
            private final Button editBtn = Icons.iconButton(Feather.SETTINGS,
                    "Edit — saving stops this receiver");
            // Shield opens the RegistrationPolicy dialog (Phase 5). Visible
            // only when strict mode is on. Same flat style as siblings.
            private final Button policyBtn = Icons.iconButton(Feather.SHIELD,
                    "Edit registration policy — strict mode only");
            private final Button removeBtn = Icons.dangerIconButton(Feather.TRASH_2,
                    "Remove this receiver");
            private final HBox box;
            private ReceiverRow boundRow;
            /** Listener that hides the policy button when strict mode goes off. */
            private final javafx.beans.value.ChangeListener<Boolean> enforceListener =
                    (obs, oldV, newV) -> paintPolicyButton(newV != null && newV);

            {
                editBtn.setFocusTraversable(false);
                policyBtn.setFocusTraversable(false);
                removeBtn.setFocusTraversable(false);
                editBtn.getStyleClass().add("flat");
                policyBtn.getStyleClass().add("flat");
                removeBtn.getStyleClass().add("flat");
                box = new HBox(4, editBtn, policyBtn, removeBtn);
                box.setAlignment(Pos.CENTER_LEFT);
                editBtn.setOnAction(e -> {
                    ReceiverRow r = getTableView().getItems().get(getIndex());
                    editRow(r);
                });
                policyBtn.setOnAction(e -> {
                    ReceiverRow r = getTableView().getItems().get(getIndex());
                    editPolicy(r);
                });
                removeBtn.setOnAction(e -> {
                    ReceiverRow r = getTableView().getItems().get(getIndex());
                    removeRow(r);
                });
            }

            /**
             * Toggle the policy button's visibility WITHOUT changing its
             * managed flag — keeping managed=true reserves the button's
             * slot in the HBox so the sibling cog + delete icons stay in
             * the same horizontal position whether the row is strict or
             * dumb mode. Otherwise the trash icon slides left on dumb-mode
             * rows and looks misaligned against strict-mode rows above/
             * below it (caught by vision inspection 2026-08-04 20:08 UTC).
             */
            private void paintPolicyButton(boolean strict) {
                policyBtn.setVisible(strict);
            }

            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (boundRow != null) {
                    boundRow.enforceHandshake.removeListener(enforceListener);
                    boundRow = null;
                }
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                ReceiverRow row = getTableView().getItems().get(getIndex());
                boundRow = row;
                row.enforceHandshake.addListener(enforceListener);
                paintPolicyButton(row.enforceHandshake.get());
                setGraphic(box);
            }
        };
    }

    /**
     * Cell factory for the Status column: colours the status text using the
     * active AtlantaFX theme's semantic colour variables. "running" →
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
    private Callback<TableColumn<ReceiverRow, String>, TableCell<ReceiverRow, String>>
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
                // "running" is the only running-and-ready state for a receiver.
                // Every other state ("stopped", "error: ...", "") is not-running.
                if ("running".equals(status)) {
                    setStyle("-fx-text-fill: -color-success-fg; -fx-font-weight: bold;");
                } else {
                    setStyle("-fx-text-fill: -color-danger-fg; -fx-font-weight: bold;");
                }
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
            out.add(new SessionStore.SavedReceiver(r.name.get(), r.port.get(),
                    r.enforceHandshake.get(), r.selfNodeId,
                    (r.policySpec != null && !r.policySpec.isPermissive()) ? r.policySpec : null));
        }
        return out;
    }

    /** Restore a previously-saved list of receivers (comes up stopped). */
    public void restore(List<SessionStore.SavedReceiver> saved) {
        if (saved == null) return;
        for (SessionStore.SavedReceiver s : saved) {
            if (s == null || s.name == null || s.name.isBlank()) continue;
            ReceiverRow row = new ReceiverRow(s.name, s.port);
            row.enforceHandshake.set(s.enforceHandshake);
            row.selfNodeId = s.selfNodeId;
            row.policySpec = s.policy;
            rows.add(row);
        }
    }

    /** Stop every running receiver — called on window close. */
    public void shutdown() {
        for (SapientReceiver rx : live.values()) {
            try { rx.stop(); } catch (Exception ignored) { }
        }
        live.clear();
    }

    /**
     * Cell factory for the "Enforce handshake" column: a disabled-while-live
     * checkbox bound to the row's property. Editing while running would be
     * ambiguous (does it stop + restart? just take effect at next start?),
     * so we make the semantics unambiguous by requiring the receiver be
     * stopped first — same UX pattern as the Port field via the edit dialog.
     * The full-form edit dialog also exposes the checkbox, for consistency.
     */
    private Callback<TableColumn<ReceiverRow, Boolean>, TableCell<ReceiverRow, Boolean>>
            makeEnforceCellFactory() {
        return col -> new TableCell<>() {
            private final CheckBox box = new CheckBox();
            private ReceiverRow boundRow;
            private final javafx.beans.value.ChangeListener<String> statusListener =
                    (obs, oldV, newV) -> box.setDisable("running".equals(newV));

            {
                box.setFocusTraversable(false);
                box.selectedProperty().addListener((obs, oldV, newV) -> {
                    if (boundRow == null) return;
                    if (newV == null || newV == boundRow.enforceHandshake.get()) return;
                    boundRow.enforceHandshake.set(newV);
                    append(boundRow, "✎ enforce handshake = " + newV);
                    persistNow.run();
                });
            }

            @Override
            protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
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
                box.setSelected(row.enforceHandshake.get());
                box.setDisable("running".equals(row.status.get()));
                row.status.addListener(statusListener);
                setGraphic(box);
            }
        };
    }

    /** Row model for a configured receiver (name, port, status, received count). */
    static final class ReceiverRow {
        final javafx.beans.property.SimpleStringProperty name;
        final javafx.beans.property.SimpleIntegerProperty port;
        final javafx.beans.property.SimpleStringProperty status;
        final javafx.beans.property.SimpleIntegerProperty received;
        /** BSI Flex 335 v2.0 handshake-enforcement toggle for this row. */
        final SimpleBooleanProperty enforceHandshake;
        /**
         * Stable UUID used as this fusion node's own {@code node_id} when strict.
         * Populated lazily on first start when null so legacy dumb-mode rows never
         * pay for a UUID they don't need.
         */
        String selfNodeId;
        /**
         * Registration policy for strict-mode receivers (Phase 5). Nullable
         * — null / permissive spec = accept all peers.
         */
        RegistrationPolicySpec policySpec;

        ReceiverRow(String name, int port) {
            this.name = new javafx.beans.property.SimpleStringProperty(name);
            this.port = new javafx.beans.property.SimpleIntegerProperty(port);
            this.status = new javafx.beans.property.SimpleStringProperty("stopped");
            this.received = new javafx.beans.property.SimpleIntegerProperty(0);
            this.enforceHandshake = new SimpleBooleanProperty(false);
        }
    }
}
