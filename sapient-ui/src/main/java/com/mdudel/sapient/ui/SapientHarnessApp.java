/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * Main JavaFX application. Three tabs: Receivers, Transmitters, Log.
 */
public final class SapientHarnessApp extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        TabPane tabs = new TabPane();
        tabs.getTabs().add(new Tab("Receivers", new ReceiversPane()));
        tabs.getTabs().add(new Tab("Transmitters", new TransmittersPane()));
        tabs.getTabs().add(new Tab("Log", new LogPane()));
        tabs.getTabs().forEach(t -> t.setClosable(false));

        BorderPane root = new BorderPane(tabs);
        root.setPadding(new Insets(8));

        Scene scene = new Scene(root, 900, 640);
        stage.setTitle("SAPIENT Test Harness (Java) — v0.1.0-SNAPSHOT");
        stage.setScene(scene);
        stage.setOnCloseRequest(evt -> {
            // Ensure any background threads (Netty groups) shut down cleanly.
            System.exit(0);
        });
        stage.show();
    }
}
