/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.ui.persist.SessionStore;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * Main JavaFX application. Three tabs (Receivers, Transmitters, Log)
 * with a View menu offering flat / material themes (AtlantaFX) in
 * light and dark variants.
 */
public final class SapientHarnessApp extends Application {

    private String currentTheme = ThemeManager.DEFAULT_THEME_NAME;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // Apply the persisted theme (or default) BEFORE any nodes are created,
        // so first paint is correct.
        currentTheme = ThemeManager.loadSaved();
        ThemeManager.apply(currentTheme);

        // Restore any previously-saved receivers/transmitters BEFORE the panes
        // are added to the scene so they show up on first paint.
        ReceiversPane receivers = new ReceiversPane();
        TransmittersPane transmitters = new TransmittersPane();
        SessionStore.Session saved = SessionStore.load();
        receivers.restore(saved.receivers);
        transmitters.restore(saved.transmitters);

        TabPane tabs = new TabPane();
        tabs.getTabs().add(new Tab("Receivers", receivers));
        tabs.getTabs().add(new Tab("Transmitters", transmitters));
        tabs.getTabs().add(new Tab("Log", new LogPane()));
        tabs.getTabs().forEach(t -> t.setClosable(false));

        BorderPane root = new BorderPane(tabs);
        root.setPadding(new Insets(8));
        root.setTop(buildMenuBar());

        Scene scene = new Scene(root, 900, 640);
        stage.setTitle("SAPIENT Test Harness (Java) — v0.1.0-SNAPSHOT");
        stage.setScene(scene);
        stage.setOnCloseRequest(evt -> {
            // Persist the current config list AND theme, then shut everything down cleanly.
            SessionStore.Session out = new SessionStore.Session();
            out.receivers = receivers.snapshot();
            out.transmitters = transmitters.snapshot();
            out.theme = currentTheme;
            SessionStore.save(out);

            receivers.shutdown();
            transmitters.shutdown();
            System.exit(0);
        });
        stage.show();
    }

    private MenuBar buildMenuBar() {
        Menu fileMenu = new Menu("_File");
        MenuItem quit = new MenuItem("Quit");
        quit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        quit.setOnAction(e -> System.exit(0));
        fileMenu.getItems().add(quit);

        Menu viewMenu = new Menu("_View");

        // Quick dark/light toggle
        MenuItem toggle = new MenuItem("Toggle Dark / Light");
        toggle.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN));
        toggle.setOnAction(e -> {
            currentTheme = ThemeManager.toggleDarkLight(currentTheme);
            ThemeManager.apply(currentTheme);
            ThemeManager.save(currentTheme);
            // Force the radio-group selection to update by re-tagging the menu.
            refreshThemeRadios(viewMenu);
        });
        viewMenu.getItems().add(toggle);
        viewMenu.getItems().add(new SeparatorMenuItem());

        // Full theme picker
        ToggleGroup themeGroup = new ToggleGroup();
        for (String name : ThemeManager.THEMES.keySet()) {
            RadioMenuItem item = new RadioMenuItem(name);
            item.setToggleGroup(themeGroup);
            item.setUserData(name);
            item.setSelected(name.equals(currentTheme));
            item.setOnAction(e -> {
                currentTheme = name;
                ThemeManager.apply(currentTheme);
                ThemeManager.save(currentTheme);
            });
            viewMenu.getItems().add(item);
        }

        MenuBar bar = new MenuBar(fileMenu, viewMenu);
        // Use the system menu bar on macOS; harmless on Windows/Linux.
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    /** Re-tick the correct radio after a keyboard-triggered theme flip. */
    private void refreshThemeRadios(Menu viewMenu) {
        for (MenuItem item : viewMenu.getItems()) {
            if (item instanceof RadioMenuItem radio && radio.getUserData() instanceof String name) {
                radio.setSelected(name.equals(currentTheme));
            }
        }
    }
}
