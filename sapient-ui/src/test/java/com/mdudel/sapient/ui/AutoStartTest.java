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
        // Post-2026-08-05 card refactor: the Message picker is now per-row,
        // so a fresh pane has ZERO ChoiceBoxes. Add a row first, then look
        // for the picker inside the freshly-built TxCard.
        AtomicReference<TransmittersPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new TransmittersPane()));
        TransmittersPane pane = paneRef.get();

        // Fill in the Add form (Name / Host / Port) and fire '+'.
        List<TextField> fields = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        collect(pane, TextField.class, fields);
        collect(pane, Button.class, buttons);
        runFx(() -> {
            fields.get(0).setText("tx-dropdown");
            fields.get(1).setText("127.0.0.1");
            fields.get(2).setText("14098");
            buttons.get(0).fire();
        });

        // Card sits inside pane.cardsBox (which sits inside a ScrollPane
        // whose skin isn't instantiated without a Stage — that's why we
        // walk cardsBox directly rather than the whole pane tree).
        @SuppressWarnings("rawtypes")
        List<ChoiceBox> choices = new ArrayList<>();
        collect(pane.cardsBox, ChoiceBox.class, choices);
        assertThat(choices).as("expected a per-row message-type picker after Add").isNotEmpty();

        // The picker's items are the MsgType enum; enum's toString() returns the label.
        List<Object> items = new ArrayList<>(choices.get(0).getItems());
        boolean found = items.stream().map(Object::toString)
                .anyMatch(s -> s.contains("Auto-Start"));
        assertThat(found).as("expected an 'Auto-Start' entry in the picker").isTrue();
    }

    @Test
    void autoStartOnNonStrictRowShowsHint() throws Exception {
        // Add a transmitter row via the '+' handler, pick AUTO_START on the
        // row's picker, click the row's Send, and verify the stream carries
        // the strict-mode hint. Post-2026-08-05: picker + Send are per-row,
        // so recollect ChoiceBoxes + Buttons AFTER the add.
        AtomicReference<TransmittersPane> paneRef = new AtomicReference<>();
        runFx(() -> paneRef.set(new TransmittersPane()));
        TransmittersPane pane = paneRef.get();

        List<TextField> fields = new ArrayList<>();
        List<Button> buttonsBefore = new ArrayList<>();
        collect(pane, TextField.class, fields);
        collect(pane, Button.class, buttonsBefore);
        assertThat(fields).hasSizeGreaterThanOrEqualTo(3);
        assertThat(buttonsBefore).isNotEmpty();

        // Fill in name/host/port, click Add. buttonsBefore.get(0) == '+' Add.
        runFx(() -> {
            fields.get(0).setText("tx-nonstrict");
            fields.get(1).setText("127.0.0.1");
            fields.get(2).setText("14099");
            buttonsBefore.get(0).fire();
        });
        assertThat(pane.snapshot()).hasSize(1);
        assertThat(pane.snapshot().get(0).enforceHandshake)
                .as("row must default to strict-mode OFF").isFalse();

        // Recollect from cardsBox directly (see companion test comment on
        // why we don't walk the whole pane tree here).
        @SuppressWarnings("rawtypes")
        List<ChoiceBox> choicesAfter = new ArrayList<>();
        List<Button> buttonsAfter = new ArrayList<>();
        collect(pane.cardsBox, ChoiceBox.class, choicesAfter);
        collect(pane.cardsBox, Button.class, buttonsAfter);
        assertThat(choicesAfter).as("expected a per-row picker after Add").isNotEmpty();

        runFx(() -> {
            @SuppressWarnings("unchecked")
            ChoiceBox<Object> picker = (ChoiceBox<Object>) choicesAfter.get(0);
            for (Object o : picker.getItems()) {
                if (o.toString().contains("Auto-Start")) {
                    picker.setValue(o);
                    break;
                }
            }
        });

        // The per-row Send button carries accessibleText "Send selected
        // message" (see Icons.accentIconButton in TxCard).
        Button sendBtn = null;
        for (Button b : buttonsAfter) {
            String tip = b.getAccessibleText();
            if (tip != null && tip.toLowerCase().contains("send")) {
                sendBtn = b; break;
            }
        }
        assertThat(sendBtn).as("expected a per-row Send button with a matching tooltip").isNotNull();
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
