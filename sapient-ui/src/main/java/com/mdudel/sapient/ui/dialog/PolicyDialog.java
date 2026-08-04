/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.dialog;

import com.mdudel.sapient.net.RegistrationPolicySpec;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Modal editor for a {@link RegistrationPolicySpec}. Presents every axis
 * of the spec as an editable control:
 * <ul>
 *   <li>Required ICD version \u2014 free-text field. Empty = no constraint.</li>
 *   <li>Allowed node types \u2014 one checkbox per {@link Registration.NodeType}
 *       enum value (except {@code UNSPECIFIED} / {@code UNRECOGNIZED}). No
 *       ticks = no constraint. Any tick(s) = allowlist.</li>
 *   <li>Allowlist / denylist \u2014 free-text areas, one UUID per line. Blank
 *       = no constraint on that axis. Empty entries and whitespace are
 *       trimmed on save.</li>
 * </ul>
 *
 * <p>Rejected registrations flow through {@code RegistrationAck.acceptance=false}
 * with the rejection reason \u2014 the operator sees the reject in the receiver
 * pane's message stream, no additional UI needed here.
 */
public final class PolicyDialog {

    private PolicyDialog() {
        // utility
    }

    public static Optional<RegistrationPolicySpec> edit(String receiverName,
                                                        RegistrationPolicySpec current) {
        RegistrationPolicySpec seed = current != null ? current : new RegistrationPolicySpec();

        Dialog<RegistrationPolicySpec> d = new Dialog<>();
        d.setTitle("Registration policy \u2014 " + receiverName);
        d.setHeaderText("Configure which peers this fusion node will accept."
                + "\nAll fields are optional; leaving a field blank means 'no constraint on this axis'.");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().setPadding(new Insets(4));

        // --- ICD version ---
        TextField icdField = new TextField(seed.requiredIcdVersion == null ? "" : seed.requiredIcdVersion);
        icdField.setPromptText("e.g. BSI Flex 335 v2.0");
        Label icdHelp = help("Rejects any peer whose Registration.icd_version doesn't match this exactly. "
                + "Leave blank to accept any version.");

        // --- Node type allowlist ---
        List<CheckBox> typeBoxes = new ArrayList<>();
        VBox typeCol1 = new VBox(2);
        VBox typeCol2 = new VBox(2);
        int idx = 0;
        Set<String> preselected = seed.allowedNodeTypes != null
                ? new LinkedHashSet<>(seed.allowedNodeTypes)
                : new LinkedHashSet<>();
        for (Registration.NodeType t : Registration.NodeType.values()) {
            if (t == Registration.NodeType.NODE_TYPE_UNSPECIFIED
                    || t == Registration.NodeType.UNRECOGNIZED) continue;
            CheckBox cb = new CheckBox(t.name());
            cb.setUserData(t.name());
            cb.setSelected(preselected.contains(t.name()));
            typeBoxes.add(cb);
            (idx++ % 2 == 0 ? typeCol1 : typeCol2).getChildren().add(cb);
        }
        javafx.scene.layout.HBox typeGrid = new javafx.scene.layout.HBox(16, typeCol1, typeCol2);
        Label typeHelp = help("Tick one or more node types the fusion node will accept "
                + "(matches any of the peer's declared Registration.node_definition[].node_type). "
                + "Tick nothing = accept any type.");

        // --- Allowlist / denylist textareas ---
        TextArea allowArea = new TextArea(joinLines(seed.allowedNodeIds));
        allowArea.setPromptText("One node UUID per line \u2014 leave blank for no allowlist");
        allowArea.setPrefRowCount(4);
        Label allowHelp = help("If set, the peer's envelope node_id must appear here. "
                + "Blank = no allowlist (any node_id passes).");

        TextArea denyArea = new TextArea(joinLines(seed.deniedNodeIds));
        denyArea.setPromptText("One node UUID per line \u2014 leave blank for no denylist");
        denyArea.setPrefRowCount(4);
        Label denyHelp = help("Peers whose node_id appears here are rejected. Denylist "
                + "takes precedence over allowlist (fail-closed on ambiguity).");

        GridPane g = Forms.grid();
        Forms.addRow(g, 0, "Required ICD version", icdField);
        g.add(icdHelp,   1, 1);
        Forms.addRow(g, 2, "Allowed node types", typeGrid);
        g.add(typeHelp,  1, 3);
        Forms.addRow(g, 4, "Node ID allowlist", allowArea);
        g.add(allowHelp, 1, 5);
        Forms.addRow(g, 6, "Node ID denylist",  denyArea);
        g.add(denyHelp,  1, 7);
        d.getDialogPane().setContent(g);
        d.getDialogPane().setPrefWidth(640);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            RegistrationPolicySpec out = new RegistrationPolicySpec();
            String icd = icdField.getText().trim();
            out.requiredIcdVersion = icd.isEmpty() ? null : icd;
            List<String> types = new ArrayList<>();
            for (CheckBox cb : typeBoxes) if (cb.isSelected()) types.add((String) cb.getUserData());
            out.allowedNodeTypes = types.isEmpty() ? null : types;
            Set<String> allow = parseLines(allowArea.getText());
            out.allowedNodeIds = allow.isEmpty() ? null : allow;
            Set<String> deny = parseLines(denyArea.getText());
            out.deniedNodeIds = deny.isEmpty() ? null : deny;
            return out;
        });

        return d.showAndWait().map(r -> r);
    }

    private static Label help(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("text-muted");
        return l;
    }

    private static String joinLines(Set<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        return String.join("\n", lines);
    }

    private static Set<String> parseLines(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String raw : text.split("\\R")) {
            String s = raw.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
