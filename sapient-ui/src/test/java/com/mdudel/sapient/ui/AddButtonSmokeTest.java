/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless smoke test that pokes the Add buttons on the two panes to verify
 * they still respond after Phase 4. Uses JavaFX's own PlatformImpl.startup
 * with the Monocle headless toolkit (no display required).
 *
 * <p>The bug we caught 2026-08-04 12:01 UTC: Marty reported that clicking
 * '+' on either pane produced no visible effect. This test walks the pane's
 * scene graph to find the fields + button, fills them in, fires the button,
 * and asserts a row was added to the underlying ObservableList.
 */
class AddButtonSmokeTest {

    @BeforeAll
    static void bootFxToolkit() {
        // The test is run under xvfb-run, so a real (virtual) X11 display
        // is available. Just boot the standard JavaFX toolkit; the pom
        // does not carry OpenJFX Monocle because this container has no
        // route to Maven Central for that artifact.
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        try { ready.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    @Test
    void receiverAddButtonAppendsRow() throws Exception {
        AtomicReference<ReceiversPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new ReceiversPane()));
        ReceiversPane pane = paneRef.get();
        assertThat(pane).isNotNull();

        // Find the form's TextFields and Button.
        List<TextField> fields = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        collect(pane, TextField.class, fields);
        collect(pane, Button.class, buttons);
        assertThat(fields).as("expected name + port TextFields in the pane").hasSizeGreaterThanOrEqualTo(2);
        assertThat(buttons).as("expected at least one Button (the '+' Add)").isNotEmpty();

        TextField name = fields.get(0);
        TextField port = fields.get(1);
        Button add = buttons.get(0); // first button is the '+' per constructor order

        int before = pane.snapshot().size();
        runFx(() -> {
            name.setText("rx-test");
            port.setText("54321");
            add.fire();
        });
        assertThat(pane.snapshot()).as("Add button must append a receiver row").hasSize(before + 1);
        assertThat(pane.snapshot().get(before).name).isEqualTo("rx-test");
        assertThat(pane.snapshot().get(before).port).isEqualTo(54321);
    }

    @Test
    void receiverAddButtonShowsHintWhenFieldsEmpty() throws Exception {
        AtomicReference<ReceiversPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new ReceiversPane()));
        ReceiversPane pane = paneRef.get();

        List<Button> buttons = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        collect(pane, Button.class, buttons);
        collect(pane, Label.class, labels);
        Button add = buttons.get(0);

        int before = pane.snapshot().size();
        runFx(add::fire);

        assertThat(pane.snapshot()).as("empty-field click must NOT add a row").hasSize(before);
        // One of the pane's Labels should now carry the hint text.
        boolean sawHint = labels.stream()
                .map(Label::getText)
                .anyMatch(t -> t != null && t.toLowerCase().contains("name") && t.toLowerCase().contains("port"));
        assertThat(sawHint).as("expected an inline hint mentioning name+port after empty Add").isTrue();
    }

    @Test
    void receiverAddButtonRejectsDuplicateName() throws Exception {
        AtomicReference<ReceiversPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new ReceiversPane()));
        ReceiversPane pane = paneRef.get();

        List<TextField> fields = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        collect(pane, TextField.class, fields);
        collect(pane, Button.class, buttons);
        TextField name = fields.get(0);
        TextField port = fields.get(1);
        Button add = buttons.get(0);

        runFx(() -> { name.setText("rx-dup"); port.setText("12000"); add.fire(); });
        runFx(() -> { name.setText("rx-dup"); port.setText("12001"); add.fire(); });

        assertThat(pane.snapshot()).as("duplicate-name click must NOT add a second row").hasSize(1);
    }

    @Test
    void transmitterAddButtonAppendsRow() throws Exception {
        AtomicReference<TransmittersPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new TransmittersPane()));
        TransmittersPane pane = paneRef.get();
        assertThat(pane).isNotNull();

        List<TextField> fields = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        collect(pane, TextField.class, fields);
        collect(pane, Button.class, buttons);
        assertThat(fields).hasSizeGreaterThanOrEqualTo(3);
        assertThat(buttons).isNotEmpty();

        TextField name = fields.get(0);
        TextField host = fields.get(1);
        TextField port = fields.get(2);
        Button add = buttons.get(0);

        int before = pane.snapshot().size();
        runFx(() -> {
            name.setText("tx-test");
            host.setText("127.0.0.1");
            port.setText("14001");
            add.fire();
        });
        assertThat(pane.snapshot()).as("Add button must append a transmitter row").hasSize(before + 1);
        assertThat(pane.snapshot().get(before).name).isEqualTo("tx-test");
        assertThat(pane.snapshot().get(before).host).isEqualTo("127.0.0.1");
        assertThat(pane.snapshot().get(before).port).isEqualTo(14001);
    }

    @Test
    void transmitterAddButtonShowsHintWhenFieldsEmpty() throws Exception {
        AtomicReference<TransmittersPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new TransmittersPane()));
        TransmittersPane pane = paneRef.get();

        List<Button> buttons = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        collect(pane, Button.class, buttons);
        collect(pane, Label.class, labels);
        Button add = buttons.get(0);

        int before = pane.snapshot().size();
        runFx(add::fire);

        assertThat(pane.snapshot()).as("empty-field click must NOT add a row").hasSize(before);
        boolean sawHint = labels.stream()
                .map(Label::getText)
                .anyMatch(t -> t != null && t.toLowerCase().contains("missing"));
        assertThat(sawHint).as("expected an inline hint listing missing fields after empty Add").isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static <T> void collect(Parent root, Class<T> type, List<T> out) {
        for (Node n : root.getChildrenUnmodifiable()) {
            if (type.isInstance(n)) out.add(type.cast(n));
            if (n instanceof Parent p) collect(p, type, out);
        }
    }

    private static void runFx(Runnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try { r.run(); } catch (Throwable t) { err.set(t); }
            finally { done.countDown(); }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("FX task timed out");
        if (err.get() != null) throw new RuntimeException(err.get());
    }
}
