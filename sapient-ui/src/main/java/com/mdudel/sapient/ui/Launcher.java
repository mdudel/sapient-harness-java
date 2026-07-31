/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

/**
 * Fat-jar launcher indirection. JavaFX 17+ requires that the main class NOT
 * extend {@link javafx.application.Application} when the JavaFX modules are on
 * the classpath (as opposed to the module path) — otherwise the JVM refuses to
 * bootstrap with a "JavaFX runtime components are missing" error. This tiny
 * launcher sidesteps that by calling {@code Application.launch()} from a
 * non-JavaFX main class.
 */
public final class Launcher {
    public static void main(String[] args) {
        SapientHarnessApp.main(args);
    }
}
