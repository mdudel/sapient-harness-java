/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.dialog;

import com.mdudel.sapient.core.factory.MessageFactory;
import com.mdudel.sapient.core.gen.DetectionGenerator;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.AlertAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;
import uk.gov.dstl.sapientmsg.bsiflex335v2.TaskAck;

import java.util.Optional;

/**
 * All per-message-type input dialogs. Each returns either:
 * <ul>
 *   <li>{@link SapientMessage} for one-shot messages, OR</li>
 *   <li>{@link DetectionGenerator.Config} for the detection generator,
 *       which is a scheduled activity rather than a single message.</li>
 * </ul>
 *
 * <p>Defaults are Wiesbaden-centered per Marty's request:
 * {@code 50.0782°N, 8.2398°E}. All fields are editable.
 */
public final class MessageDialogs {

    /** Wiesbaden default center for lat/lon fields. */
    public static final double DEFAULT_LAT = 50.0782;
    public static final double DEFAULT_LON = 8.2398;

    private MessageDialogs() {
    }

    // ---------- Registration ----------
    public static Optional<SapientMessage> registration(String nodeId) {
        Dialog<SapientMessage> d = base("Send Registration");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        ChoiceBox<Registration.NodeType> typeBox = new ChoiceBox<>();
        for (Registration.NodeType t : Registration.NodeType.values()) {
            if (t == Registration.NodeType.UNRECOGNIZED) continue;
            typeBox.getItems().add(t);
        }
        typeBox.setValue(Registration.NodeType.NODE_TYPE_OTHER);
        TextField nameField = new TextField("sapient-harness-java");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "node_type:", typeBox);
        Forms.addRow(g, 2, "name:", nameField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> bt == ButtonType.OK
                ? MessageFactory.registration(nodeIdField.getText(),
                        typeBox.getValue(), nameField.getText())
                : null);
        return d.showAndWait();
    }

    // ---------- RegistrationAck ----------
    public static Optional<SapientMessage> registrationAck(String nodeId) {
        Dialog<SapientMessage> d = base("Send RegistrationAck");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextField destField = new TextField();
        destField.setPromptText("target node UUID (optional)");
        CheckBox acceptBox = new CheckBox("Accept");
        acceptBox.setSelected(true);
        TextField reasonField = new TextField();
        reasonField.setPromptText("reason (used when rejecting)");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "destination_id:", destField);
        Forms.addRow(g, 2, "acceptance:", acceptBox);
        Forms.addRow(g, 3, "reason:", reasonField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> bt == ButtonType.OK
                ? MessageFactory.registrationAck(nodeIdField.getText(),
                        destField.getText(), acceptBox.isSelected(),
                        reasonField.getText())
                : null);
        return d.showAndWait();
    }

    // ---------- StatusReport ----------
    public static Optional<SapientMessage> statusReport(String nodeId) {
        Dialog<SapientMessage> d = base("Send StatusReport");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        ChoiceBox<StatusReport.System> systemBox = new ChoiceBox<>();
        for (StatusReport.System s : StatusReport.System.values()) {
            if (s == StatusReport.System.UNRECOGNIZED) continue;
            systemBox.getItems().add(s);
        }
        systemBox.setValue(StatusReport.System.SYSTEM_OK);
        TextField modeField = new TextField("default");
        TextField latField = new TextField(String.valueOf(DEFAULT_LAT));
        TextField lonField = new TextField(String.valueOf(DEFAULT_LON));
        TextField altField = new TextField();
        altField.setPromptText("altitude (m) optional");

        ChoiceBox<StatusReport.PowerSource> powerSrcBox = new ChoiceBox<>();
        for (StatusReport.PowerSource s : StatusReport.PowerSource.values()) {
            if (s == StatusReport.PowerSource.UNRECOGNIZED) continue;
            powerSrcBox.getItems().add(s);
        }
        powerSrcBox.setValue(StatusReport.PowerSource.POWERSOURCE_INTERNAL_BATTERY);
        ChoiceBox<StatusReport.PowerStatus> powerStatBox = new ChoiceBox<>();
        for (StatusReport.PowerStatus s : StatusReport.PowerStatus.values()) {
            if (s == StatusReport.PowerStatus.UNRECOGNIZED) continue;
            powerStatBox.getItems().add(s);
        }
        powerStatBox.setValue(StatusReport.PowerStatus.POWERSTATUS_OK);
        Spinner<Integer> batterySpinner = intSpinner(0, 100, 87);

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "system:", systemBox);
        Forms.addRow(g, 2, "mode:", modeField);
        Forms.addRow(g, 3, "latitude:", latField);
        Forms.addRow(g, 4, "longitude:", lonField);
        Forms.addRow(g, 5, "altitude:", altField);
        Forms.addRow(g, 6, "power source:", powerSrcBox);
        Forms.addRow(g, 7, "power status:", powerStatBox);
        Forms.addRow(g, 8, "battery %:", batterySpinner);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            Double alt = parseDoubleOrNull(altField.getText());
            return MessageFactory.statusReport(
                    nodeIdField.getText(),
                    systemBox.getValue(),
                    modeField.getText(),
                    Double.parseDouble(latField.getText()),
                    Double.parseDouble(lonField.getText()),
                    alt,
                    powerSrcBox.getValue(),
                    powerStatBox.getValue(),
                    batterySpinner.getValue());
        });
        return d.showAndWait();
    }

    // ---------- Detection generator ----------
    /**
     * Returns a {@link DetectionGenerator.Config} for the caller to hand to
     * a {@link DetectionGenerator} instance. Distinct from the one-shot
     * dialogs: detections are a scheduled activity.
     */
    public static Optional<DetectionGenerator.Config> detectionGenerator(String nodeId) {
        Dialog<DetectionGenerator.Config> d = base("Start Detection Generator");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        Spinner<Integer> countSpinner = intSpinner(1, 200, 5);
        TextField latField = new TextField(String.valueOf(DEFAULT_LAT));
        TextField lonField = new TextField(String.valueOf(DEFAULT_LON));
        Spinner<Integer> radiusSpinner = intSpinner(10, 100_000, 500);
        Spinner<Integer> rateSpinner = intSpinner(50, 60_000, 1000);
        CheckBox movingBox = new CheckBox("Enable motion");
        movingBox.setSelected(true);
        Spinner<Double> speedSpinner = doubleSpinner(0.0, 500.0, 2.0, 0.5);
        Spinner<Integer> turnJitterSpinner = intSpinner(0, 180, 15);
        TextField classField = new TextField("person");
        Spinner<Double> confSpinner = doubleSpinner(0.0, 1.0, 0.85, 0.05);

        int row = 0;
        Forms.addRow(g, row++, "node_id:", nodeIdField);
        Forms.addRow(g, row++, "track count:", countSpinner);
        Forms.addRow(g, row++, "center latitude:", latField);
        Forms.addRow(g, row++, "center longitude:", lonField);
        Forms.addRow(g, row++, "radius (m):", radiusSpinner);
        Forms.addRow(g, row++, "update rate (ms):", rateSpinner);
        Forms.addRow(g, row++, "motion:", movingBox);
        Forms.addRow(g, row++, "speed (m/s):", speedSpinner);
        Forms.addRow(g, row++, "turn jitter (°):", turnJitterSpinner);
        Forms.addRow(g, row++, "classification:", classField);
        Forms.addRow(g, row++, "confidence:", confSpinner);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            return new DetectionGenerator.Config(
                    nodeIdField.getText(),
                    countSpinner.getValue(),
                    Double.parseDouble(latField.getText()),
                    Double.parseDouble(lonField.getText()),
                    radiusSpinner.getValue(),
                    rateSpinner.getValue(),
                    movingBox.isSelected(),
                    speedSpinner.getValue(),
                    turnJitterSpinner.getValue(),
                    classField.getText(),
                    confSpinner.getValue().floatValue());
        });
        return d.showAndWait();
    }

    // ---------- Task ----------
    public static Optional<SapientMessage> task(String nodeId) {
        Dialog<SapientMessage> d = base("Send Task");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextField destField = new TextField();
        destField.setPromptText("target node UUID (optional)");
        TextField nameField = new TextField("smoke-test task");
        ChoiceBox<Task.Control> controlBox = new ChoiceBox<>();
        for (Task.Control c : Task.Control.values()) {
            if (c == Task.Control.UNRECOGNIZED) continue;
            controlBox.getItems().add(c);
        }
        controlBox.setValue(Task.Control.CONTROL_START);
        TextArea descArea = new TextArea("Test task issued by sapient-harness-java");
        descArea.setPrefRowCount(3);

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "destination_id:", destField);
        Forms.addRow(g, 2, "task_name:", nameField);
        Forms.addRow(g, 3, "control:", controlBox);
        Forms.addRow(g, 4, "description:", descArea);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> bt == ButtonType.OK
                ? MessageFactory.task(nodeIdField.getText(), destField.getText(),
                        nameField.getText(), controlBox.getValue(), descArea.getText())
                : null);
        return d.showAndWait();
    }

    // ---------- TaskAck ----------
    public static Optional<SapientMessage> taskAck(String nodeId) {
        Dialog<SapientMessage> d = base("Send TaskAck");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextField destField = new TextField();
        destField.setPromptText("target node UUID (optional)");
        TextField taskIdField = new TextField();
        taskIdField.setPromptText("task_id being ACKed (optional; auto-generated if blank)");
        ChoiceBox<TaskAck.TaskStatus> statusBox = new ChoiceBox<>();
        for (TaskAck.TaskStatus s : TaskAck.TaskStatus.values()) {
            if (s == TaskAck.TaskStatus.UNRECOGNIZED) continue;
            statusBox.getItems().add(s);
        }
        statusBox.setValue(TaskAck.TaskStatus.TASK_STATUS_ACCEPTED);
        TextField reasonField = new TextField();
        reasonField.setPromptText("rejection reason (optional)");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "destination_id:", destField);
        Forms.addRow(g, 2, "task_id:", taskIdField);
        Forms.addRow(g, 3, "status:", statusBox);
        Forms.addRow(g, 4, "reason:", reasonField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String taskId = taskIdField.getText().isBlank() ? null : taskIdField.getText();
            java.util.List<String> reasons = reasonField.getText().isBlank()
                    ? null
                    : java.util.List.of(reasonField.getText());
            return MessageFactory.taskAck(nodeIdField.getText(), destField.getText(),
                    taskId, statusBox.getValue(), reasons);
        });
        return d.showAndWait();
    }

    // ---------- Alert ----------
    public static Optional<SapientMessage> alert(String nodeId) {
        Dialog<SapientMessage> d = base("Send Alert");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        ChoiceBox<Alert.AlertType> typeBox = new ChoiceBox<>();
        for (Alert.AlertType t : Alert.AlertType.values()) {
            if (t == Alert.AlertType.UNRECOGNIZED) continue;
            typeBox.getItems().add(t);
        }
        typeBox.setValue(Alert.AlertType.ALERT_TYPE_WARNING);
        ChoiceBox<Alert.AlertStatus> statusBox = new ChoiceBox<>();
        for (Alert.AlertStatus s : Alert.AlertStatus.values()) {
            if (s == Alert.AlertStatus.UNRECOGNIZED) continue;
            statusBox.getItems().add(s);
        }
        statusBox.setValue(Alert.AlertStatus.ALERT_STATUS_ACTIVE);
        ChoiceBox<Alert.DiscretePriority> priBox = new ChoiceBox<>();
        for (Alert.DiscretePriority p : Alert.DiscretePriority.values()) {
            if (p == Alert.DiscretePriority.UNRECOGNIZED) continue;
            priBox.getItems().add(p);
        }
        priBox.setValue(Alert.DiscretePriority.DISCRETE_PRIORITY_MEDIUM);
        TextField descField = new TextField("Test alert from sapient-harness-java");
        TextField latField = new TextField(String.valueOf(DEFAULT_LAT));
        TextField lonField = new TextField(String.valueOf(DEFAULT_LON));
        TextField altField = new TextField();
        altField.setPromptText("altitude (m) optional");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "alert_type:", typeBox);
        Forms.addRow(g, 2, "status:", statusBox);
        Forms.addRow(g, 3, "priority:", priBox);
        Forms.addRow(g, 4, "description:", descField);
        Forms.addRow(g, 5, "latitude:", latField);
        Forms.addRow(g, 6, "longitude:", lonField);
        Forms.addRow(g, 7, "altitude:", altField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            return MessageFactory.alert(nodeIdField.getText(),
                    typeBox.getValue(), statusBox.getValue(), priBox.getValue(),
                    descField.getText(),
                    Double.parseDouble(latField.getText()),
                    Double.parseDouble(lonField.getText()),
                    parseDoubleOrNull(altField.getText()));
        });
        return d.showAndWait();
    }

    // ---------- AlertAck ----------
    public static Optional<SapientMessage> alertAck(String nodeId) {
        Dialog<SapientMessage> d = base("Send AlertAck");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextField destField = new TextField();
        destField.setPromptText("target node UUID (optional)");
        TextField alertIdField = new TextField();
        alertIdField.setPromptText("alert_id being ACKed (optional; auto-generated if blank)");
        ChoiceBox<AlertAck.AlertAckStatus> statusBox = new ChoiceBox<>();
        for (AlertAck.AlertAckStatus s : AlertAck.AlertAckStatus.values()) {
            if (s == AlertAck.AlertAckStatus.UNRECOGNIZED) continue;
            statusBox.getItems().add(s);
        }
        statusBox.setValue(AlertAck.AlertAckStatus.ALERT_ACK_STATUS_ACCEPTED);
        TextField reasonField = new TextField();
        reasonField.setPromptText("rejection reason (optional)");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "destination_id:", destField);
        Forms.addRow(g, 2, "alert_id:", alertIdField);
        Forms.addRow(g, 3, "status:", statusBox);
        Forms.addRow(g, 4, "reason:", reasonField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String alertId = alertIdField.getText().isBlank() ? null : alertIdField.getText();
            java.util.List<String> reasons = reasonField.getText().isBlank()
                    ? null
                    : java.util.List.of(reasonField.getText());
            return MessageFactory.alertAck(nodeIdField.getText(), destField.getText(),
                    alertId, statusBox.getValue(), reasons);
        });
        return d.showAndWait();
    }

    // ---------- Error ----------
    public static Optional<SapientMessage> error(String nodeId) {
        Dialog<SapientMessage> d = base("Send Error");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextArea msgArea = new TextArea("Test error from sapient-harness-java");
        msgArea.setPrefRowCount(3);

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "error message:", msgArea);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> bt == ButtonType.OK
                ? MessageFactory.error(nodeIdField.getText(), msgArea.getText())
                : null);
        return d.showAndWait();
    }

    // ---------- Small helpers ----------

    private static <T> Dialog<T> base(String title) {
        Dialog<T> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(null);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().setPadding(new Insets(4));
        return d;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int init) {
        Spinner<Integer> s = new Spinner<>(new SpinnerValueFactory
                .IntegerSpinnerValueFactory(min, max, init));
        s.setEditable(true);
        return s;
    }

    private static Spinner<Double> doubleSpinner(double min, double max,
                                                 double init, double step) {
        Spinner<Double> s = new Spinner<>(new SpinnerValueFactory
                .DoubleSpinnerValueFactory(min, max, init, step));
        s.setEditable(true);
        return s;
    }

    private static Double parseDoubleOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        try { return Double.parseDouble(text.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
