/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Icon-button factory. Uses the Feather icon pack (via Ikonli) — a clean,
 * monochrome, flat icon family that pairs well with AtlantaFX themes.
 *
 * <p>Each icon button carries a tooltip so the accessibility contract stays
 * intact even though the label text is gone. Tooltip appears after 300 ms of
 * hover, which is fast enough to feel responsive without being distracting.
 */
public final class Icons {

    /** Default pixel size for button icons. */
    public static final int SIZE = 16;

    private Icons() {
        // utility
    }

    /**
     * Build a flat icon button with a tooltip.
     *
     * @param code    the Feather icon enum value (e.g. {@code Feather.PLUS})
     * @param tooltip the hover tooltip text (also used for accessibility)
     */
    public static Button iconButton(Feather code, String tooltip) {
        FontIcon icon = new FontIcon(code);
        icon.setIconSize(SIZE);
        Button b = new Button();
        b.setGraphic(icon);
        Tooltip tip = new Tooltip(tooltip);
        tip.setShowDelay(Duration.millis(300));
        b.setTooltip(tip);
        // Also expose the tooltip text as the button's accessible text so screen
        // readers still name the control correctly.
        b.setAccessibleText(tooltip);
        return b;
    }

    /**
     * Same as {@link #iconButton(Feather, String)} but also applies an
     * AtlantaFX style class so the button gets accent colouring.
     */
    public static Button accentIconButton(Feather code, String tooltip) {
        Button b = iconButton(code, tooltip);
        b.getStyleClass().add("accent");
        return b;
    }

    /**
     * Same as {@link #iconButton(Feather, String)} but styled as a "danger"
     * button — useful for destructive actions like Remove.
     */
    public static Button dangerIconButton(Feather code, String tooltip) {
        Button b = iconButton(code, tooltip);
        b.getStyleClass().add("danger");
        return b;
    }

    /**
     * Same as {@link #iconButton(Feather, String)} but flat / borderless —
     * useful for icon-only cells inside tables.
     */
    public static Button flatIconButton(Feather code, String tooltip) {
        Button b = iconButton(code, tooltip);
        b.getStyleClass().add("flat");
        return b;
    }
}
