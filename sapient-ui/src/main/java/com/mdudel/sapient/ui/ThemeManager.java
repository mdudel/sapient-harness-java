/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Theme;
import javafx.application.Application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central theme registry + persistence.
 *
 * <p>Wraps AtlantaFX (a flat / material JavaFX theme library — see
 * <a href="https://github.com/mkpaz/atlantafx">mkpaz/atlantafx</a>).
 * Offers 7 built-in themes across light and dark variants; the current
 * choice is persisted to
 * <code>${user.home}/.sapient-harness/theme</code> so the UI comes up
 * the same way next launch.
 */
public final class ThemeManager {

    /** All themes, in the order they'll appear in the menu. */
    public static final Map<String, Theme> THEMES = new LinkedHashMap<>();

    static {
        // Light themes
        THEMES.put("Primer Light",     new PrimerLight());
        THEMES.put("Nord Light",       new NordLight());
        THEMES.put("Cupertino Light",  new CupertinoLight());
        // Dark themes
        THEMES.put("Primer Dark",      new PrimerDark());
        THEMES.put("Nord Dark",        new NordDark());
        THEMES.put("Cupertino Dark",   new CupertinoDark());
        THEMES.put("Dracula",          new Dracula());
    }

    /** The dark themes, for the "Toggle dark/light" menu action. */
    private static final String[] DARK_NAMES = {
            "Primer Dark", "Nord Dark", "Cupertino Dark", "Dracula"
    };

    /** Default theme when nothing is persisted. */
    public static final String DEFAULT_THEME_NAME = "Primer Light";

    /** File where the current theme name is remembered between launches. */
    private static final Path CONFIG =
            Path.of(System.getProperty("user.home"), ".sapient-harness", "theme");

    private ThemeManager() {
        // utility
    }

    /** Apply a theme by its display name; falls back to default on unknown. */
    public static void apply(String name) {
        Theme theme = THEMES.getOrDefault(name, THEMES.get(DEFAULT_THEME_NAME));
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
    }

    /** True iff the given theme name is one of the dark variants. */
    public static boolean isDark(String name) {
        return Arrays.asList(DARK_NAMES).contains(name);
    }

    /**
     * Read the persisted theme name from disk. Returns {@link #DEFAULT_THEME_NAME}
     * if no config exists, is unreadable, or names an unknown theme.
     */
    public static String loadSaved() {
        try {
            if (Files.exists(CONFIG)) {
                String name = Files.readString(CONFIG).trim();
                if (THEMES.containsKey(name)) {
                    return name;
                }
            }
        } catch (IOException ignored) {
            // fall through to default
        }
        return DEFAULT_THEME_NAME;
    }

    /**
     * Persist the given theme name to disk. Best-effort; failures are
     * silently ignored (we don't want a config-write hiccup to kill the UI).
     */
    public static void save(String name) {
        try {
            Files.createDirectories(CONFIG.getParent());
            Files.writeString(CONFIG, name);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Given a current theme name, return the "opposite polarity" default —
     * used by the quick toggle in the View menu.
     */
    public static String toggleDarkLight(String currentName) {
        if (isDark(currentName)) {
            return "Primer Light";
        }
        return "Primer Dark";
    }
}
