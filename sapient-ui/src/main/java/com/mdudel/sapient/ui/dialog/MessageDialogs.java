/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.ui.dialog;

import com.mdudel.sapient.core.factory.MessageFactory;
import com.mdudel.sapient.core.gen.DetectionGenerator;
import com.mdudel.sapient.core.gen.SensorGenerator;
import com.mdudel.sapient.core.gen.SensorGeometry;
import com.mdudel.sapient.ui.persist.SavedDetectionConfig;
import com.mdudel.sapient.ui.persist.SavedSensorConfig;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationList;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationOrRangeBearing;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RangeBearingCone;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RangeBearingDatum;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Accordion;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.AlertAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;
import uk.gov.dstl.sapientmsg.bsiflex335v2.TaskAck;

import java.util.Optional;

/**
 * All per-message-type input dialogs. Each returns either:
 * <ul>
 *   <li>{@link SapientMessage} for one-shot messages, OR</li>
 *   <li>{@link DetectionGenerator.Config} for the detection generator,
 *       which is a scheduled activity rather than a single message.</li>
 * </ul>
 *
 * <p>Defaults are Wiesbaden-centered per Marty's request:
 * {@code 50.0782°N, 8.2398°E}. All fields are editable.
 */
public final class MessageDialogs {

    /** Wiesbaden default center for lat/lon fields. */
    public static final double DEFAULT_LAT = 50.0782;
    public static final double DEFAULT_LON = 8.2398;

    /**
     * Standard classification vocabulary for DetectionReport.classification.type.
     *
     * <p>SAPIENT BSI Flex 335 v2 doesn't define a fixed enum for classification
     * strings — the field is deliberately open so ye can register domain-specific
     * classifications in Registration and use them here. This list is the widely-
     * seen "sensible defaults" set (drawn from dstl reference scenarios). The
     * ComboBox is editable, so ye can type any custom string as well.
     */
    public static final java.util.List<String> STANDARD_CLASSIFICATIONS = java.util.List.of(
            "unknown",
            "person",
            "group of persons",
            "vehicle",
            "land vehicle",
            "tracked vehicle",
            "wheeled vehicle",
            "motorcycle",
            "boat",
            "surface vessel",
            "aircraft",
            "fixed wing aircraft",
            "rotary wing aircraft",
            "helicopter",
            "uav",
            "quadcopter",
            "multirotor",
            "drone",
            "munition",
            "missile",
            "projectile",
            "animal",
            "clutter",
            "false alarm"
    );

    private MessageDialogs() {
    }

    // ---------- Registration ----------
    public static Optional<SapientMessage> registration(String nodeId) {
        Dialog<SapientMessage> d = base("Send Registration");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        ChoiceBox<Registration.NodeType> typeBox = new ChoiceBox<>();
        for (Registration.NodeType t : Registration.NodeType.values()) {
            if (t == Registration.NodeType.UNRECOGNIZED) continue;
            typeBox.getItems().add(t);
        }
        typeBox.setValue(Registration.NodeType.NODE_TYPE_OTHER);
        TextField nameField = new TextField("sapient-harness-java");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "node_type:", typeBox);
        Forms.addRow(g, 2, "name:", nameField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> bt == ButtonType.OK
                ? MessageFactory.registration(nodeIdField.getText(),
                        typeBox.getValue(), nameField.getText())
                : null);
        return d.showAndWait();
    }

    // ---------- RegistrationAck ----------
    public static Optional<SapientMessage> registrationAck(String nodeId) {
        Dialog<SapientMessage> d = base("Send RegistrationAck");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextField destField = new TextField();
        destField.setPromptText("target node UUID (optional)");
        CheckBox acceptBox = new CheckBox("Accept");
        acceptBox.setSelected(true);
        TextField reasonField = new TextField();
        reasonField.setPromptText("reason (used when rejecting)");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "destination_id:", destField);
        Forms.addRow(g, 2, "acceptance:", acceptBox);
        Forms.addRow(g, 3, "reason:", reasonField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> bt == ButtonType.OK
                ? MessageFactory.registrationAck(nodeIdField.getText(),
                        destField.getText(), acceptBox.isSelected(),
                        reasonField.getText())
                : null);
        return d.showAndWait();
    }

    // ---------- StatusReport ----------
    /** FOV mode for the one-shot StatusReport dialog. */
    private enum StatusFovMode { NONE, CONE, POLYGON, POLYGON_WITH_OBSCURATION }

    /**
     * One-shot StatusReport dialog. As of 2026-08-03 an optional FOV
     * attachment can ship with the message: NONE (no field_of_view on the
     * wire, back-compat default), CONE (a static {@link RangeBearingCone}),
     * POLYGON (the cone's ground footprint projected via
     * {@link SensorGeometry#projectConeFootprint}, FOV only), or
     * POLYGON_WITH_OBSCURATION (same FOV polygon PLUS the disc-minus-LOB
     * complement in {@code obscuration[0]}, via
     * {@link SensorGeometry#projectAntiLobPolygon}). Same projection math
     * the live SensorGenerator ticker uses so the two paths stay in
     * perfect lock-step. Unlike the SensorGenerator ticker, this is a
     * single static snapshot: no motion, no sweep.
     *
     * <p>Layout: 9 core rows always visible, FOV lives in a collapsed
     * {@link TitledPane} so operators who don't need FOV see the same
     * dialog they always did.
     */
    public static Optional<SapientMessage> statusReport(String nodeId) {
        Dialog<SapientMessage> d = base("Send StatusReport");

        // --- Core fields (unchanged) ---
        TextField nodeIdField = new TextField(nodeId);
        ChoiceBox<StatusReport.System> systemBox = new ChoiceBox<>();
        for (StatusReport.System s : StatusReport.System.values()) {
            if (s == StatusReport.System.UNRECOGNIZED) continue;
            systemBox.getItems().add(s);
        }
        systemBox.setValue(StatusReport.System.SYSTEM_OK);
        TextField modeField = new TextField("default");
        TextField latField = new TextField(String.valueOf(DEFAULT_LAT));
        TextField lonField = new TextField(String.valueOf(DEFAULT_LON));
        TextField altField = new TextField();
        altField.setPromptText("altitude (m) optional");

        ChoiceBox<StatusReport.PowerSource> powerSrcBox = new ChoiceBox<>();
        for (StatusReport.PowerSource s : StatusReport.PowerSource.values()) {
            if (s == StatusReport.PowerSource.UNRECOGNIZED) continue;
            powerSrcBox.getItems().add(s);
        }
        powerSrcBox.setValue(StatusReport.PowerSource.POWERSOURCE_INTERNAL_BATTERY);
        ChoiceBox<StatusReport.PowerStatus> powerStatBox = new ChoiceBox<>();
        for (StatusReport.PowerStatus s : StatusReport.PowerStatus.values()) {
            if (s == StatusReport.PowerStatus.UNRECOGNIZED) continue;
            powerStatBox.getItems().add(s);
        }
        powerStatBox.setValue(StatusReport.PowerStatus.POWERSTATUS_OK);
        Spinner<Integer> batterySpinner = intSpinner(0, 100, 87);

        GridPane coreG = Forms.grid();
        Forms.addRow(coreG, 0, "node_id:", nodeIdField);
        Forms.addRow(coreG, 1, "system:", systemBox);
        Forms.addRow(coreG, 2, "mode:", modeField);
        Forms.addRow(coreG, 3, "latitude:", latField);
        Forms.addRow(coreG, 4, "longitude:", lonField);
        Forms.addRow(coreG, 5, "altitude:", altField);
        Forms.addRow(coreG, 6, "power source:", powerSrcBox);
        Forms.addRow(coreG, 7, "power status:", powerStatBox);
        Forms.addRow(coreG, 8, "battery %:", batterySpinner);

        // --- Optional FOV attachment ---
        // Defaults model a stationary rotating-radar snapshot pointing
        // east: 90° boresight, 5° elevation, 5000 m range, 30° horizontal
        // beam, 15° vertical beam, TRUE north datum. Match the
        // SensorGenerator dialog defaults so ops build one mental model.
        ChoiceBox<StatusFovMode> fovModeBox = new ChoiceBox<>();
        fovModeBox.getItems().addAll(StatusFovMode.values());
        fovModeBox.setValue(StatusFovMode.NONE);
        Spinner<Double> fovAzSpinner = doubleSpinner(0.0, 360.0, 90.0, 5.0);
        Spinner<Double> fovElSpinner = doubleSpinner(-90.0, 90.0, 5.0, 1.0);
        Spinner<Double> fovRangeSpinner = doubleSpinner(10.0, 200_000.0, 5000.0, 100.0);
        Spinner<Double> fovHExtSpinner = doubleSpinner(0.1, 360.0, 30.0, 1.0);
        Spinner<Double> fovVExtSpinner = doubleSpinner(0.1, 180.0, 15.0, 1.0);
        ChoiceBox<RangeBearingDatum> fovDatumBox = new ChoiceBox<>();
        fovDatumBox.getItems().addAll(
                RangeBearingDatum.RANGE_BEARING_DATUM_TRUE,
                RangeBearingDatum.RANGE_BEARING_DATUM_MAGNETIC,
                RangeBearingDatum.RANGE_BEARING_DATUM_GRID,
                RangeBearingDatum.RANGE_BEARING_DATUM_PLATFORM);
        fovDatumBox.setValue(RangeBearingDatum.RANGE_BEARING_DATUM_TRUE);
        Spinner<Integer> fovPolyVertsSpinner = intSpinner(3, 24, 8);

        GridPane fovG = Forms.grid();
        Forms.addRow(fovG, 0, "FOV mode:", fovModeBox);
        Forms.addRow(fovG, 1, "boresight azimuth (°):", fovAzSpinner);
        Forms.addRow(fovG, 2, "boresight elevation (°):", fovElSpinner);
        Forms.addRow(fovG, 3, "range (m):", fovRangeSpinner);
        Forms.addRow(fovG, 4, "horizontal extent (°):", fovHExtSpinner);
        Forms.addRow(fovG, 5, "vertical extent (°):", fovVExtSpinner);
        Forms.addRow(fovG, 6, "datum:", fovDatumBox);
        Forms.addRow(fovG, 7, "polygon vertices (POLYGON modes only):", fovPolyVertsSpinner);
        TitledPane fovPane = new TitledPane("Optional field of view", fovG);
        fovPane.setExpanded(false);   // hidden by default: dialog looks unchanged

        VBoxWrapper content = new VBoxWrapper(coreG, fovPane);

        // 2026-08-01 19:02 UTC (SkyLord): with FOV expanded the dialog
        // pushed past the bottom of a 720p display and left OK/Cancel
        // unreachable — no scroll bar to catch the overflow. Same fix as
        // the Sensor Generator dialog got in 086d9b3: wrap in a bounded
        // ScrollPane so vertical growth is capped and the vbar appears
        // as needed. Dialog stays resizable so the user can grow it
        // manually if they want to see everything at once.
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(480);
        scroll.setPrefViewportWidth(460);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        d.getDialogPane().setContent(scroll);
        d.setResizable(true);
        d.getDialogPane().setPrefHeight(540);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            Double alt = parseDoubleOrNull(altField.getText());
            double lat = Double.parseDouble(latField.getText());
            double lon = Double.parseDouble(lonField.getText());

            LocationOrRangeBearing fov = null;
            java.util.List<LocationOrRangeBearing> obscuration =
                    java.util.Collections.emptyList();
            StatusFovMode mode = fovModeBox.getValue();
            if (mode == StatusFovMode.CONE) {
                RangeBearingCone cone = MessageFactory.cone(
                        fovAzSpinner.getValue(),
                        fovElSpinner.getValue(),
                        fovRangeSpinner.getValue(),
                        fovHExtSpinner.getValue(),
                        fovVExtSpinner.getValue(),
                        fovDatumBox.getValue());
                fov = MessageFactory.fovCone(cone);
            } else if (mode == StatusFovMode.POLYGON
                    || mode == StatusFovMode.POLYGON_WITH_OBSCURATION) {
                java.util.List<double[]> vertices = SensorGeometry.projectConeFootprint(
                        lat, lon,
                        fovAzSpinner.getValue(),
                        fovHExtSpinner.getValue(),
                        fovRangeSpinner.getValue(),
                        fovPolyVertsSpinner.getValue());
                LocationList poly = MessageFactory.polygon(vertices, alt);
                fov = MessageFactory.fovPolygon(poly);

                // POLYGON_WITH_OBSCURATION ONLY: also emit the geometric
                // complement of the LOB (disc-minus-LOB) as obscuration so
                // a receiver renders green FOV + red "everything else"
                // side-by-side. Plain POLYGON ships only the FOV polygon,
                // no obscuration. Same helper the live SensorGenerator
                // ticker uses so wire shapes match tick-for-tick.
                if (mode == StatusFovMode.POLYGON_WITH_OBSCURATION) {
                    java.util.List<java.util.List<double[]>> antiLob =
                            SensorGeometry.projectAntiLobPolygon(
                                    lat, lon,
                                    fovAzSpinner.getValue(),
                                    fovHExtSpinner.getValue(),
                                    fovRangeSpinner.getValue(),
                                    Math.max(12, fovPolyVertsSpinner.getValue() * 2));
                    if (!antiLob.isEmpty()) {
                        java.util.List<LocationOrRangeBearing> obs =
                                new java.util.ArrayList<>(antiLob.size());
                        for (java.util.List<double[]> p : antiLob) {
                            obs.add(MessageFactory.fovPolygon(
                                    MessageFactory.polygon(p, alt)));
                        }
                        obscuration = obs;
                    }
                }
            }

            // FOV=NONE routes through the pre-existing 9-arg factory so the
            // wire shape is byte-identical to the old behaviour — no risk
            // of an accidental empty-FOV block leaking onto old receivers.
            if (fov == null) {
                return MessageFactory.statusReport(
                        nodeIdField.getText(),
                        systemBox.getValue(),
                        modeField.getText(),
                        lat, lon, alt,
                        powerSrcBox.getValue(),
                        powerStatBox.getValue(),
                        batterySpinner.getValue());
            }
            return MessageFactory.statusReport(
                    nodeIdField.getText(),
                    systemBox.getValue(),
                    StatusReport.Info.INFO_NEW,
                    modeField.getText(),
                    lat, lon, alt,
                    powerSrcBox.getValue(),
                    powerStatBox.getValue(),
                    batterySpinner.getValue(),
                    fov,
                    java.util.Collections.emptyList(),
                    obscuration);
        });
        return d.showAndWait();
    }

    // ---------- Detection generator ----------
    /**
     * Returns a {@link DetectionGenerator.Config} for the caller to hand to
     * a {@link DetectionGenerator} instance. Distinct from the one-shot
     * dialogs: detections are a scheduled activity.
     */
    public static Optional<DetectionGenerator.Config> detectionGenerator(String nodeId) {
        return detectionGenerator(nodeId, null);
    }

    /**
     * Seedable overload for the detection-generator dialog. When {@code seed}
     * is non-null, every field is pre-populated from the persisted values so
     * an edit sticks across runs. When {@code seed} is null the built-in
     * defaults are used, giving byte-identical behaviour to the single-arg
     * overload. Added 2026-08-06 for the per-transmitter persistence work.
     */
    public static Optional<DetectionGenerator.Config> detectionGenerator(
            String nodeId, SavedDetectionConfig seed) {
        Dialog<DetectionGenerator.Config> d = base("Start Detection Generator");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        Spinner<Integer> countSpinner = intSpinner(1, 200, seedInt(seed == null ? null : seed.trackCount, 5));
        TextField latField = new TextField(String.valueOf(seedDouble(seed == null ? null : seed.centerLat, DEFAULT_LAT)));
        TextField lonField = new TextField(String.valueOf(seedDouble(seed == null ? null : seed.centerLon, DEFAULT_LON)));
        Spinner<Integer> radiusSpinner = intSpinner(10, 100_000, seedInt(seed == null ? null : (seed.radiusMeters == null ? null : seed.radiusMeters.intValue()), 500));
        Spinner<Integer> rateSpinner = intSpinner(50, 60_000, seedInt(seed == null ? null : (seed.tickMs == null ? null : seed.tickMs.intValue()), 1000));
        CheckBox movingBox = new CheckBox("Enable motion");
        movingBox.setSelected(seedBool(seed == null ? null : seed.moving, true));
        Spinner<Double> speedSpinner = doubleSpinner(0.0, 500.0, seedDouble(seed == null ? null : seed.speedMps, 2.0), 0.5);
        Spinner<Integer> turnJitterSpinner = intSpinner(0, 180, seedInt(seed == null ? null : (seed.turnJitterDeg == null ? null : (int) Math.round(seed.turnJitterDeg)), 15));
        ComboBox<String> classCombo = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(STANDARD_CLASSIFICATIONS));
        classCombo.setEditable(true);
        classCombo.setValue(seed != null && seed.classification != null && !seed.classification.isBlank()
                ? seed.classification : "person");
        Spinner<Double> confSpinner = doubleSpinner(0.0, 1.0,
                seed != null && seed.confidence != null ? seed.confidence.doubleValue() : 0.85, 0.05);
        // Altitude / vertical motion (SAPIENT Location.z, metres, WGS84).
        // Defaults chosen for Wiesbaden: ground ≈ 110 m MSL, so 150 m base
        // with a ±50 m spread puts tracks 100–200 m above sea level (≈40–140 m
        // AGL locally). minAltitudeM=0 keeps everything non-negative even if
        // the operator dials wilder jitter; "floor above ground" defaults to
        // 100 m so a bare-defaults run mimics slow drones circling. Ceiling
        // 0 = unbounded above.
        Spinner<Double> initAltSpinner = doubleSpinner(0.0, 20_000.0, seedDouble(seed == null ? null : seed.initialAltitudeM, 150.0), 10.0);
        Spinner<Double> altJitterSpinner = doubleSpinner(0.0, 5_000.0, seedDouble(seed == null ? null : seed.altitudeJitterM, 50.0), 5.0);
        Spinner<Double> vertRateSpinner = doubleSpinner(0.0, 100.0, seedDouble(seed == null ? null : seed.verticalRateMps, 0.0), 0.5);
        Spinner<Double> minAltSpinner = doubleSpinner(0.0, 20_000.0, seedDouble(seed == null ? null : seed.minAltitudeM, 100.0), 10.0);
        Spinner<Double> maxAltSpinner = doubleSpinner(0.0, 30_000.0, seedDouble(seed == null ? null : seed.maxAltitudeM, 0.0), 100.0);

        int row = 0;
        Forms.addRow(g, row++, "node_id:", nodeIdField);
        Forms.addRow(g, row++, "track count:", countSpinner);
        Forms.addRow(g, row++, "center latitude:", latField);
        Forms.addRow(g, row++, "center longitude:", lonField);
        Forms.addRow(g, row++, "radius (m):", radiusSpinner);
        Forms.addRow(g, row++, "update rate (ms):", rateSpinner);
        Forms.addRow(g, row++, "motion:", movingBox);
        Forms.addRow(g, row++, "speed (m/s):", speedSpinner);
        Forms.addRow(g, row++, "turn jitter (°):", turnJitterSpinner);
        Forms.addRow(g, row++, "initial altitude (m):", initAltSpinner);
        Forms.addRow(g, row++, "altitude jitter (± m):", altJitterSpinner);
        Forms.addRow(g, row++, "vertical rate (m/s):", vertRateSpinner);
        Forms.addRow(g, row++, "floor (min alt, m):", minAltSpinner);
        Forms.addRow(g, row++, "ceiling (max alt, m; 0=none):", maxAltSpinner);
        Forms.addRow(g, row++, "classification:", classCombo);
        Forms.addRow(g, row++, "confidence:", confSpinner);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            return new DetectionGenerator.Config(
                    nodeIdField.getText(),
                    countSpinner.getValue(),
                    Double.parseDouble(latField.getText()),
                    Double.parseDouble(lonField.getText()),
                    radiusSpinner.getValue(),
                    rateSpinner.getValue(),
                    movingBox.isSelected(),
                    speedSpinner.getValue(),
                    turnJitterSpinner.getValue(),
                    classCombo.getValue() == null ? "unknown" : classCombo.getValue().trim(),
                    confSpinner.getValue().floatValue(),
                    initAltSpinner.getValue(),
                    altJitterSpinner.getValue(),
                    vertRateSpinner.getValue(),
                    minAltSpinner.getValue(),
                    maxAltSpinner.getValue());
        });
        return d.showAndWait();
    }

    // ---------- Sensor generator ----------
    /**
     * Returns a {@link SensorGenerator.Config} for the caller to hand to a
     * {@link SensorGenerator} instance. Sibling to
     * {@link #detectionGenerator(String)} — sensor generator emits a stream
     * of {@code StatusReport} heartbeats with a live-updating field of view
     * (either a swept cone or its ground polygon projection) so the receiver
     * can render the sensor's live coverage lobe.
     *
     * <p>Layout: 24 fields are grouped into four {@link TitledPane} sections
     * inside an {@link Accordion}. Identity is expanded by default so the
     * common fields are visible without scrolling; FOV geometry, motion, and
     * status housekeeping collapse to keep the dialog under {@code 560 px}
     * tall so OK / Cancel stay on-screen at any reasonable display size
     * (SkyLord flagged the flat 24-row layout as too tall 2026-08-01
     * 13:54 UTC). The whole accordion sits inside a {@link ScrollPane} as a
     * belt-and-braces defence against small monitors.
     */
    public static Optional<SensorGenerator.Config> sensorGenerator(String nodeId) {
        return sensorGenerator(nodeId, null);
    }

    /**
     * Seedable overload for the sensor-generator dialog. When {@code seed}
     * is non-null, every field is pre-populated from the persisted values so
     * an edit sticks across runs. When {@code seed} is null the built-in
     * defaults are used, giving byte-identical behaviour to the single-arg
     * overload. Added 2026-08-06 for the per-transmitter persistence work.
     */
    public static Optional<SensorGenerator.Config> sensorGenerator(
            String nodeId, SavedSensorConfig seed) {
        Dialog<SensorGenerator.Config> d = base("Start Sensor Generator");

        // Identity + cadence
        TextField nodeIdField = new TextField(nodeId);
        Spinner<Integer> tickSpinner = intSpinner(50, 60_000,
                seedInt(seed == null ? null : (seed.tickMs == null ? null : seed.tickMs.intValue()), 1000));

        // Platform position
        TextField latField = new TextField(String.valueOf(seedDouble(seed == null ? null : seed.centerLat, DEFAULT_LAT)));
        TextField lonField = new TextField(String.valueOf(seedDouble(seed == null ? null : seed.centerLon, DEFAULT_LON)));
        Spinner<Double> altSpinner = doubleSpinner(0.0, 20_000.0,
                seedDouble(seed == null ? null : seed.centerAltM, 100.0), 10.0);

        // Motion
        CheckBox movingBox = new CheckBox("Moving platform");
        movingBox.setSelected(seedBool(seed == null ? null : seed.moving, false));
        Spinner<Double> speedSpinner = doubleSpinner(0.0, 500.0,
                seedDouble(seed == null ? null : seed.speedMps, 5.0), 0.5);
        Spinner<Integer> turnJitterSpinner = intSpinner(0, 180,
                seedInt(seed == null ? null : (seed.turnJitterDeg == null ? null : (int) Math.round(seed.turnJitterDeg)), 5));
        Spinner<Integer> motionRadiusSpinner = intSpinner(10, 100_000,
                seedInt(seed == null ? null : (seed.motionRadiusMeters == null ? null : (int) Math.round(seed.motionRadiusMeters)), 2000));

        // FOV mode
        ChoiceBox<SensorGenerator.FovMode> fovModeBox = new ChoiceBox<>();
        fovModeBox.getItems().addAll(SensorGenerator.FovMode.values());
        SensorGenerator.FovMode fovSeed = SensorGenerator.FovMode.CONE;
        if (seed != null && seed.fovMode != null) {
            try { fovSeed = SensorGenerator.FovMode.valueOf(seed.fovMode); }
            catch (IllegalArgumentException ignored) { /* fall back to CONE */ }
        }
        fovModeBox.setValue(fovSeed);

        // Cone / shared FOV geometry. Default: rotating radar dish at ~5 RPM
        // (30 deg/sec CW), 30 deg horizontal beam, 15 deg vertical beam,
        // 5000 m range, boresight elevation 5 deg (looking slightly upward
        // for airborne tracks), no elevation nod.
        Spinner<Double> initAzSpinner = doubleSpinner(0.0, 360.0,
                seedDouble(seed == null ? null : seed.initialAzimuthDeg, 0.0), 5.0);
        Spinner<Double> azRateSpinner = doubleSpinner(-360.0, 360.0,
                seedDouble(seed == null ? null : seed.azimuthRateDegPerSec, 30.0), 5.0);
        Spinner<Double> initElSpinner = doubleSpinner(-90.0, 90.0,
                seedDouble(seed == null ? null : seed.initialElevationDeg, 5.0), 1.0);
        Spinner<Double> elMinSpinner = doubleSpinner(-90.0, 90.0,
                seedDouble(seed == null ? null : seed.elevationMinDeg, 0.0), 1.0);
        Spinner<Double> elMaxSpinner = doubleSpinner(-90.0, 90.0,
                seedDouble(seed == null ? null : seed.elevationMaxDeg, 30.0), 1.0);
        Spinner<Double> elRateSpinner = doubleSpinner(0.0, 90.0,
                seedDouble(seed == null ? null : seed.elevationRateDegPerSec, 0.0), 1.0);
        Spinner<Double> rangeSpinner = doubleSpinner(10.0, 200_000.0,
                seedDouble(seed == null ? null : seed.rangeMeters, 5000.0), 100.0);
        Spinner<Double> hExtentSpinner = doubleSpinner(0.1, 360.0,
                seedDouble(seed == null ? null : seed.horizontalExtentDeg, 30.0), 1.0);
        Spinner<Double> vExtentSpinner = doubleSpinner(0.1, 180.0,
                seedDouble(seed == null ? null : seed.verticalExtentDeg, 15.0), 1.0);

        // Polygon-only
        Spinner<Integer> polyVertsSpinner = intSpinner(3, 24,
                seedInt(seed == null ? null : seed.polygonVertexCount, 8));

        // Status housekeeping
        ChoiceBox<StatusReport.System> systemBox = new ChoiceBox<>();
        for (StatusReport.System s : StatusReport.System.values()) {
            if (s == StatusReport.System.UNRECOGNIZED) continue;
            systemBox.getItems().add(s);
        }
        StatusReport.System sysSeed = StatusReport.System.SYSTEM_OK;
        if (seed != null && seed.system != null) {
            try { sysSeed = StatusReport.System.valueOf(seed.system); }
            catch (IllegalArgumentException ignored) { /* fall back to SYSTEM_OK */ }
        }
        systemBox.setValue(sysSeed);
        TextField modeField = new TextField(seed != null && seed.mode != null && !seed.mode.isBlank()
                ? seed.mode : "scanning");
        Spinner<Integer> batteryInitSpinner = intSpinner(0, 100,
                seedInt(seed == null ? null : seed.initialBatteryLevel, 100));
        Spinner<Double> batteryDrainSpinner = doubleSpinner(0.0, 100.0,
                seedDouble(seed == null ? null : seed.batteryDrainPerMin, 0.0), 0.1);

        // Section 1: Identity — the always-visible essentials.
        GridPane identityG = Forms.grid();
        Forms.addRow(identityG, 0, "node_id:", nodeIdField);
        Forms.addRow(identityG, 1, "tick rate (ms):", tickSpinner);
        Forms.addRow(identityG, 2, "sensor latitude:", latField);
        Forms.addRow(identityG, 3, "sensor longitude:", lonField);
        Forms.addRow(identityG, 4, "sensor altitude (m):", altSpinner);
        TitledPane identityPane = new TitledPane("Identity & position", identityG);
        identityPane.setCollapsible(false);   // essentials always visible

        // Section 2: Platform motion — collapsed unless the user cares.
        GridPane motionG = Forms.grid();
        Forms.addRow(motionG, 0, "platform:", movingBox);
        Forms.addRow(motionG, 1, "platform speed (m/s):", speedSpinner);
        Forms.addRow(motionG, 2, "platform turn jitter (°):", turnJitterSpinner);
        Forms.addRow(motionG, 3, "platform corral radius (m):", motionRadiusSpinner);
        TitledPane motionPane = new TitledPane("Platform motion (moving platforms only)", motionG);

        // Section 3: FOV geometry — the biggest section, expanded by default
        // because it's the whole point of running a sensor generator.
        GridPane fovG = Forms.grid();
        Forms.addRow(fovG, 0,  "FOV mode:", fovModeBox);
        Forms.addRow(fovG, 1,  "initial azimuth (°):", initAzSpinner);
        Forms.addRow(fovG, 2,  "azimuth rate (°/s, +CW / -CCW):", azRateSpinner);
        Forms.addRow(fovG, 3,  "initial elevation (°):", initElSpinner);
        Forms.addRow(fovG, 4,  "elevation min (°):", elMinSpinner);
        Forms.addRow(fovG, 5,  "elevation max (°):", elMaxSpinner);
        Forms.addRow(fovG, 6,  "elevation nod rate (°/s):", elRateSpinner);
        Forms.addRow(fovG, 7,  "range (m):", rangeSpinner);
        Forms.addRow(fovG, 8,  "horizontal extent (°):", hExtentSpinner);
        Forms.addRow(fovG, 9,  "vertical extent (°):", vExtentSpinner);
        Forms.addRow(fovG, 10, "polygon vertices (POLYGON only):", polyVertsSpinner);
        TitledPane fovPane = new TitledPane("Field of view geometry", fovG);

        // Section 4: Status housekeeping — collapsed. Rarely touched after
        // first run; defaults are fine for smoke tests.
        GridPane statusG = Forms.grid();
        Forms.addRow(statusG, 0, "system:", systemBox);
        Forms.addRow(statusG, 1, "mode:", modeField);
        Forms.addRow(statusG, 2, "initial battery (%):", batteryInitSpinner);
        Forms.addRow(statusG, 3, "battery drain (%/min, 0=off):", batteryDrainSpinner);
        TitledPane statusPane = new TitledPane("Status housekeeping", statusG);

        // Accordion collapses the motion + FOV + status panes so only
        // Identity + one Accordion pane are open at a time. Start with FOV
        // expanded because it's the interesting one; the user can flip to
        // motion or status without the dialog exploding vertically.
        Accordion acc = new Accordion(motionPane, fovPane, statusPane);
        acc.setExpandedPane(fovPane);

        VBoxWrapper content = new VBoxWrapper(identityPane, acc);

        // ScrollPane guardrail so the dialog stays bounded on small displays
        // even if all Accordion panes were somehow expanded at once.
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(520);
        scroll.setPrefViewportWidth(460);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        d.getDialogPane().setContent(scroll);
        // Cap the dialog itself so on very small screens the OK/Cancel row
        // never falls off the bottom — the ScrollPane takes the overflow.
        d.setResizable(true);
        d.getDialogPane().setPrefHeight(580);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            Double drain = batteryDrainSpinner.getValue() > 0
                    ? batteryDrainSpinner.getValue() : null;
            return new SensorGenerator.Config(
                    nodeIdField.getText(),
                    tickSpinner.getValue(),
                    Double.parseDouble(latField.getText()),
                    Double.parseDouble(lonField.getText()),
                    altSpinner.getValue(),
                    movingBox.isSelected(),
                    speedSpinner.getValue(),
                    turnJitterSpinner.getValue(),
                    motionRadiusSpinner.getValue(),
                    fovModeBox.getValue(),
                    initAzSpinner.getValue(),
                    azRateSpinner.getValue(),
                    initElSpinner.getValue(),
                    elMinSpinner.getValue(),
                    elMaxSpinner.getValue(),
                    elRateSpinner.getValue(),
                    rangeSpinner.getValue(),
                    hExtentSpinner.getValue(),
                    vExtentSpinner.getValue(),
                    polyVertsSpinner.getValue(),
                    systemBox.getValue(),
                    modeField.getText().trim().isEmpty() ? "scanning"
                            : modeField.getText().trim(),
                    batteryInitSpinner.getValue(),
                    drain);
        });
        return d.showAndWait();
    }

    // ---------- Task ----------
    public static Optional<SapientMessage> task(String nodeId) {
        Dialog<SapientMessage> d = base("Send Task");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextField destField = new TextField();
        destField.setPromptText("target node UUID (optional)");
        TextField nameField = new TextField("smoke-test task");
        ChoiceBox<Task.Control> controlBox = new ChoiceBox<>();
        for (Task.Control c : Task.Control.values()) {
            if (c == Task.Control.UNRECOGNIZED) continue;
            controlBox.getItems().add(c);
        }
        controlBox.setValue(Task.Control.CONTROL_START);
        TextArea descArea = new TextArea("Test task issued by sapient-harness-java");
        descArea.setPrefRowCount(3);

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "destination_id:", destField);
        Forms.addRow(g, 2, "task_name:", nameField);
        Forms.addRow(g, 3, "control:", controlBox);
        Forms.addRow(g, 4, "description:", descArea);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> bt == ButtonType.OK
                ? MessageFactory.task(nodeIdField.getText(), destField.getText(),
                        nameField.getText(), controlBox.getValue(), descArea.getText())
                : null);
        return d.showAndWait();
    }

    // ---------- TaskAck ----------
    public static Optional<SapientMessage> taskAck(String nodeId) {
        Dialog<SapientMessage> d = base("Send TaskAck");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextField destField = new TextField();
        destField.setPromptText("target node UUID (optional)");
        TextField taskIdField = new TextField();
        taskIdField.setPromptText("task_id being ACKed (optional; auto-generated if blank)");
        ChoiceBox<TaskAck.TaskStatus> statusBox = new ChoiceBox<>();
        for (TaskAck.TaskStatus s : TaskAck.TaskStatus.values()) {
            if (s == TaskAck.TaskStatus.UNRECOGNIZED) continue;
            statusBox.getItems().add(s);
        }
        statusBox.setValue(TaskAck.TaskStatus.TASK_STATUS_ACCEPTED);
        TextField reasonField = new TextField();
        reasonField.setPromptText("rejection reason (optional)");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "destination_id:", destField);
        Forms.addRow(g, 2, "task_id:", taskIdField);
        Forms.addRow(g, 3, "status:", statusBox);
        Forms.addRow(g, 4, "reason:", reasonField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String taskId = taskIdField.getText().isBlank() ? null : taskIdField.getText();
            java.util.List<String> reasons = reasonField.getText().isBlank()
                    ? null
                    : java.util.List.of(reasonField.getText());
            return MessageFactory.taskAck(nodeIdField.getText(), destField.getText(),
                    taskId, statusBox.getValue(), reasons);
        });
        return d.showAndWait();
    }

    // ---------- Alert ----------
    public static Optional<SapientMessage> alert(String nodeId) {
        Dialog<SapientMessage> d = base("Send Alert");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        ChoiceBox<Alert.AlertType> typeBox = new ChoiceBox<>();
        for (Alert.AlertType t : Alert.AlertType.values()) {
            if (t == Alert.AlertType.UNRECOGNIZED) continue;
            typeBox.getItems().add(t);
        }
        typeBox.setValue(Alert.AlertType.ALERT_TYPE_WARNING);
        ChoiceBox<Alert.AlertStatus> statusBox = new ChoiceBox<>();
        for (Alert.AlertStatus s : Alert.AlertStatus.values()) {
            if (s == Alert.AlertStatus.UNRECOGNIZED) continue;
            statusBox.getItems().add(s);
        }
        statusBox.setValue(Alert.AlertStatus.ALERT_STATUS_ACTIVE);
        ChoiceBox<Alert.DiscretePriority> priBox = new ChoiceBox<>();
        for (Alert.DiscretePriority p : Alert.DiscretePriority.values()) {
            if (p == Alert.DiscretePriority.UNRECOGNIZED) continue;
            priBox.getItems().add(p);
        }
        priBox.setValue(Alert.DiscretePriority.DISCRETE_PRIORITY_MEDIUM);
        TextField descField = new TextField("Test alert from sapient-harness-java");
        TextField latField = new TextField(String.valueOf(DEFAULT_LAT));
        TextField lonField = new TextField(String.valueOf(DEFAULT_LON));
        TextField altField = new TextField();
        altField.setPromptText("altitude (m) optional");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "alert_type:", typeBox);
        Forms.addRow(g, 2, "status:", statusBox);
        Forms.addRow(g, 3, "priority:", priBox);
        Forms.addRow(g, 4, "description:", descField);
        Forms.addRow(g, 5, "latitude:", latField);
        Forms.addRow(g, 6, "longitude:", lonField);
        Forms.addRow(g, 7, "altitude:", altField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            return MessageFactory.alert(nodeIdField.getText(),
                    typeBox.getValue(), statusBox.getValue(), priBox.getValue(),
                    descField.getText(),
                    Double.parseDouble(latField.getText()),
                    Double.parseDouble(lonField.getText()),
                    parseDoubleOrNull(altField.getText()));
        });
        return d.showAndWait();
    }

    // ---------- AlertAck ----------
    public static Optional<SapientMessage> alertAck(String nodeId) {
        Dialog<SapientMessage> d = base("Send AlertAck");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextField destField = new TextField();
        destField.setPromptText("target node UUID (optional)");
        TextField alertIdField = new TextField();
        alertIdField.setPromptText("alert_id being ACKed (optional; auto-generated if blank)");
        ChoiceBox<AlertAck.AlertAckStatus> statusBox = new ChoiceBox<>();
        for (AlertAck.AlertAckStatus s : AlertAck.AlertAckStatus.values()) {
            if (s == AlertAck.AlertAckStatus.UNRECOGNIZED) continue;
            statusBox.getItems().add(s);
        }
        statusBox.setValue(AlertAck.AlertAckStatus.ALERT_ACK_STATUS_ACCEPTED);
        TextField reasonField = new TextField();
        reasonField.setPromptText("rejection reason (optional)");

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "destination_id:", destField);
        Forms.addRow(g, 2, "alert_id:", alertIdField);
        Forms.addRow(g, 3, "status:", statusBox);
        Forms.addRow(g, 4, "reason:", reasonField);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String alertId = alertIdField.getText().isBlank() ? null : alertIdField.getText();
            java.util.List<String> reasons = reasonField.getText().isBlank()
                    ? null
                    : java.util.List.of(reasonField.getText());
            return MessageFactory.alertAck(nodeIdField.getText(), destField.getText(),
                    alertId, statusBox.getValue(), reasons);
        });
        return d.showAndWait();
    }

    // ---------- Error ----------
    public static Optional<SapientMessage> error(String nodeId) {
        Dialog<SapientMessage> d = base("Send Error");
        GridPane g = Forms.grid();

        TextField nodeIdField = new TextField(nodeId);
        TextArea msgArea = new TextArea("Test error from sapient-harness-java");
        msgArea.setPrefRowCount(3);

        Forms.addRow(g, 0, "node_id:", nodeIdField);
        Forms.addRow(g, 1, "error message:", msgArea);
        d.getDialogPane().setContent(g);

        d.setResultConverter(bt -> bt == ButtonType.OK
                ? MessageFactory.error(nodeIdField.getText(), msgArea.getText())
                : null);
        return d.showAndWait();
    }

    // ---------- Small helpers ----------

    private static <T> Dialog<T> base(String title) {
        Dialog<T> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(null);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().setPadding(new Insets(4));
        return d;
    }

    /**
     * Minimal VBox alias used purely so the sensor-generator dialog can build
     * a two-item vertical layout (identity pane above the accordion) without
     * pulling {@code javafx.scene.layout.VBox} into an import that would
     * otherwise be unused by the rest of the file. Kept package-private and
     * final — it's an implementation detail of the dialog wrapping.
     */
    private static final class VBoxWrapper extends javafx.scene.layout.VBox {
        VBoxWrapper(Node... children) {
            super(6, children);
            setPadding(new Insets(4));
        }
    }

    private static Spinner<Integer> intSpinner(int min, int max, int init) {
        Spinner<Integer> s = new Spinner<>(new SpinnerValueFactory
                .IntegerSpinnerValueFactory(min, max, init));
        s.setEditable(true);
        return s;
    }

    private static Spinner<Double> doubleSpinner(double min, double max,
                                                 double init, double step) {
        Spinner<Double> s = new Spinner<>(new SpinnerValueFactory
                .DoubleSpinnerValueFactory(min, max, init, step));
        s.setEditable(true);
        return s;
    }

    private static Double parseDoubleOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        try { return Double.parseDouble(text.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    // ---------- Seed helpers (per-transmitter persistence, 2026-08-06) ----------
    //
    // Trivial null-coalesce helpers so the seeded overloads of the sensor +
    // detection dialogs stay readable. Placed alongside the intSpinner /
    // doubleSpinner factories they feed. Package-private is fine — the two
    // callers both live in this class.

    private static int seedInt(Integer v, int def)         { return v == null ? def : v; }
    private static double seedDouble(Double v, double def) { return v == null ? def : v; }
    private static boolean seedBool(Boolean v, boolean def){ return v == null ? def : v; }
}
