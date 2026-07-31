/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

/**
 * Placeholder Log tab. v0.2 will bind a Log4j2 appender here for live tailing;
 * for v0.1, users see the Receivers/Transmitters per-tab streams directly and
 * global logs go to the terminal that launched the app.
 */
public final class LogPane extends BorderPane {

    public LogPane() {
        setPadding(new Insets(8));
        TextArea area = new TextArea(
                "Global log tailing lands in v0.2.\n\n"
              + "For now, application log output is written to the terminal that\n"
              + "launched the app (via Log4j2 → console).\n\n"
              + "Per-receiver and per-transmitter event streams are on their own tabs."
        );
        area.setEditable(false);
        VBox v = new VBox(new Label("Log"), area);
        setCenter(v);
    }
}
