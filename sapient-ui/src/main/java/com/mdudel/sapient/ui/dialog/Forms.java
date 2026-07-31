/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.dialog;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

/**
 * Convenience helpers for building consistent label:input grids in dialogs.
 */
public final class Forms {

    private Forms() {
    }

    /** Standard 2-column form grid: label right-aligned, input stretches. */
    public static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.setPadding(new Insets(8));
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setHalignment(HPos.RIGHT);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c0, c1);
        return g;
    }

    /** Add a labelled row to the grid. */
    public static void addRow(GridPane g, int row, String label, Node input) {
        g.add(new Label(label), 0, row);
        g.add(input, 1, row);
    }
}
