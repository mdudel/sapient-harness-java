/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui;

import com.mdudel.sapient.core.gen.DetectionGenerator;
import com.mdudel.sapient.core.gen.SensorGenerator;
import com.mdudel.sapient.net.SapientMessageListener;
import com.mdudel.sapient.net.SapientReceiver;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the Auto-Start dropdown option added 2026-08-04 14:20 UTC.
 * Two axes:
 * <ul>
 *   <li>{@link AutoStartRecipe} produces the specified defaults (unit tests).</li>
 *   <li>Selecting AUTO_START in the TransmittersPane picker and clicking
 *       Send is a no-op with a visible hint when strict mode is off
 *       (headless FX smoke tests).</li>
 * </ul>
 * A full end-to-end cascade test (edge client + strict receiver + generators
 * pumping) would need to inject the auto-start choice programmatically and
 * poll the fusion side for status + detection frames; deferred to a manual
 * smoke run because generator lifetime + FX-thread interplay is fiddly to
 * assert deterministically in surefire.
 */
class AutoStartTest {

    @BeforeAll
    static void bootFxToolkit() {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException already) {
            ready.countDown();
        }
        try { ready.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    // ── AutoStartRecipe unit tests ─────────────────────────────────────

    @Test
    void sensorConfigCarriesMovingAndConeFov() {
        SensorGenerator.Config c = AutoStartRecipe.sensorConfig(
                "11111111-1111-1111-1111-111111111111");
        assertThat(c.nodeId).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(c.moving).isTrue();
        assertThat(c.fovMode).isEqualTo(SensorGenerator.FovMode.CONE);
        assertThat(c.rangeMeters).isEqualTo(20_000.0);
    }

    @Test
    void detectionConfigCarriesDronesAt20km() {
        DetectionGenerator.Config c = AutoStartRecipe.detectionConfig(
                "22222222-2222-2222-2222-222222222222");
        assertThat(c.nodeId).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(c.radiusMeters).isEqualTo(20_000.0);
        assertThat(c.classification).isEqualTo("drone");
        assertThat(c.moving).isTrue();
    }

    // ── TransmittersPane integration smoke ─────────────────────────────

    @Test
    void autoStartOptionAppearsInDropdown() throws Exception {
        AtomicReference<TransmittersPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new TransmittersPane()));
        TransmittersPane pane = paneRef.get();

        @SuppressWarnings("rawtypes")
        List<ChoiceBox> choices = new ArrayList<>();
        collect(pane, ChoiceBox.class, choices);
        assertThat(choices).as("expected the message-type picker ChoiceBox").isNotEmpty();

        // The picker's items are the MsgType enum; enum's toString() returns the label.
        List<Object> items = new ArrayList<>(choices.get(0).getItems());
        boolean found = items.stream().map(Object::toString)
                .anyMatch(s -> s.contains("Auto-Start"));
        assertThat(found).as("expected an 'Auto-Start' entry in the picker").isTrue();
    }

    @Test
    void autoStartOnNonStrictRowShowsHint() throws Exception {
        // Add a transmitter row via the '+' handler, pick AUTO_START,
        // click Send, and verify the stream carries the strict-mode hint.
        AtomicReference<TransmittersPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new TransmittersPane()));
        TransmittersPane pane = paneRef.get();

        List<TextField> fields = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        @SuppressWarnings("rawtypes")
        List<ChoiceBox> choices = new ArrayList<>();
        collect(pane, TextField.class, fields);
        collect(pane, Button.class, buttons);
        collect(pane, ChoiceBox.class, choices);
        assertThat(fields).hasSizeGreaterThanOrEqualTo(3);
        assertThat(buttons).isNotEmpty();

        // Fill in name/host/port, click Add.
        runFx(() -> {
            fields.get(0).setText("tx-nonstrict");
            fields.get(1).setText("127.0.0.1");
            fields.get(2).setText("14099");
            buttons.get(0).fire(); // '+' Add
        });
        assertThat(pane.snapshot()).hasSize(1);
        // enforceHandshake is a boolean field on SavedTransmitter (public);
        // assert directly on the snapshot value.
        assertThat(pane.snapshot().get(0).enforceHandshake)
                .as("row must default to strict-mode OFF").isFalse();

        // Set the picker to AUTO_START and fire Send.
        // The ChoiceBox at index 0 is the msg-type picker (per constructor
        // order). Its items are the MsgType enum; find and set AUTO_START.
        runFx(() -> {
            @SuppressWarnings("unchecked")
            ChoiceBox<Object> picker = (ChoiceBox<Object>) choices.get(0);
            for (Object o : picker.getItems()) {
                if (o.toString().contains("Auto-Start")) {
                    picker.setValue(o);
                    break;
                }
            }
        });
        // The Send button is one of the pane's Buttons — the one whose
        // accessible text mentions "send" (case-insensitive). Find it.
        Button sendBtn = null;
        for (Button b : buttons) {
            String tip = b.getAccessibleText();
            if (tip != null && tip.toLowerCase().contains("send")) {
                sendBtn = b; break;
            }
        }
        assertThat(sendBtn).as("expected a Send button with a matching tooltip").isNotNull();
        final Button send = sendBtn;
        runFx(send::fire);

        // The stream ListView items live inside a ListView<String> in the pane.
        // The hint we emit contains "auto-start requires strict mode".
        @SuppressWarnings("rawtypes")
        List<javafx.scene.control.ListView> lists = new ArrayList<>();
        collect(pane, javafx.scene.control.ListView.class, lists);
        assertThat(lists).isNotEmpty();
        @SuppressWarnings({"unchecked", "rawtypes"})
        javafx.scene.control.ListView<String> stream =
                (javafx.scene.control.ListView<String>) lists.get(lists.size() - 1);
        boolean sawHint = stream.getItems().stream()
                .anyMatch(s -> s != null && s.contains("auto-start requires strict mode"));
        assertThat(sawHint).as("expected the strict-mode-required hint in the stream").isTrue();
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

    private static int pickFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
