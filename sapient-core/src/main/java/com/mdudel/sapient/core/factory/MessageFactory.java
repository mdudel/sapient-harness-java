/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.factory;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.AlertAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.DetectionReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Location;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationCoordinateSystem;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationDatum;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationList;
import uk.gov.dstl.sapientmsg.bsiflex335v2.LocationOrRangeBearing;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RangeBearingCone;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RangeBearingCoordinateSystem;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RangeBearingDatum;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;
import uk.gov.dstl.sapientmsg.bsiflex335v2.TaskAck;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Factory for building minimum-valid {@link SapientMessage} instances for each
 * top-level content type. Callers pass whatever form fields they collected;
 * this class fills in the mandatory scaffolding (timestamp, node_id, correct
 * enum values, ULIDs where required) and returns a ready-to-send message.
 *
 * <p><b>Minimum-valid</b> = the smallest set of fields that will pass
 * dstl-style validation. Advanced tests can build richer messages via the
 * JSON template editor.
 *
 * <p>ULID vs UUID: the SAPIENT spec calls for ULIDs on {@code report_id},
 * {@code object_id}, {@code task_id}, {@code alert_id}. This test bench uses
 * randomly-generated UUIDs and stores them as strings in those fields — the
 * validators in dstl's reference harness accept any string that isn't obviously
 * malformed, and using UUIDs keeps the code dependency-free. If ye need true
 * Crockford-base32 ULIDs, add a ulid-creator dep and swap {@link #newUlid()}.
 */
public final class MessageFactory {

    private MessageFactory() {
        // utility
    }

    // ----- Envelope helpers -----------------------------------------------

    /** Now-timestamp for {@code SapientMessage.timestamp}. */
    public static Timestamp now() {
        Instant i = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(i.getEpochSecond())
                .setNanos(i.getNano())
                .build();
    }

    /** Random UUID as string — for {@code node_id}, {@code destination_id}. */
    public static String newUuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Placeholder ULID — returns a random UUID as string. dstl's reference
     * validators do NOT enforce the Crockford-base32 shape; if that becomes
     * a compat problem, drop in ulid-creator here.
     */
    public static String newUlid() {
        return UUID.randomUUID().toString();
    }

    // ----- Location helper ------------------------------------------------

    /** Build a WGS84 lat/lng {@link Location} in decimal degrees + metres. */
    public static Location latLon(double latDeg, double lonDeg, Double altMeters) {
        Location.Builder b = Location.newBuilder()
                .setX(lonDeg)   // dstl proto: X = longitude
                .setY(latDeg)   // dstl proto: Y = latitude
                .setCoordinateSystem(LocationCoordinateSystem.LOCATION_COORDINATE_SYSTEM_LAT_LNG_DEG_M)
                .setDatum(LocationDatum.LOCATION_DATUM_WGS84_E);
        if (altMeters != null) {
            b.setZ(altMeters);
        }
        return b.build();
    }

    // ----- Field-of-view helpers -----------------------------------------

    /**
     * Build a {@link RangeBearingCone} FOV pinned to decimal-degrees + metres,
     * with the given datum (TRUE for a stationary sensor, PLATFORM for a
     * moving-platform sensor whose azimuth is relative to platform heading).
     * All angular values in degrees; range in metres.
     */
    public static RangeBearingCone cone(double azimuthDeg, double elevationDeg,
                                         double rangeMeters,
                                         double horizontalExtentDeg,
                                         double verticalExtentDeg,
                                         RangeBearingDatum datum) {
        return RangeBearingCone.newBuilder()
                .setAzimuth(azimuthDeg)
                .setElevation(elevationDeg)
                .setRange(rangeMeters)
                .setHorizontalExtent(horizontalExtentDeg)
                .setVerticalExtent(verticalExtentDeg)
                .setCoordinateSystem(RangeBearingCoordinateSystem
                        .RANGE_BEARING_COORDINATE_SYSTEM_DEGREES_M)
                .setDatum(datum == null ? RangeBearingDatum.RANGE_BEARING_DATUM_TRUE : datum)
                .build();
    }

    /** Wrap a {@link RangeBearingCone} in the FOV oneof carrier. */
    public static LocationOrRangeBearing fovCone(RangeBearingCone cone) {
        return LocationOrRangeBearing.newBuilder().setRangeBearing(cone).build();
    }

    /**
     * Build a {@link LocationList} FOV from a list of (lat, lon) vertices.
     * Altitude is set to the sensor's platform altitude for all vertices so a
     * receiver drawing the polygon on a globe puts it at the right height
     * (surface-projected footprints look right when altM=0 or ground level).
     */
    public static LocationList polygon(List<double[]> latLonVertices, Double altMeters) {
        LocationList.Builder b = LocationList.newBuilder();
        for (double[] p : latLonVertices) {
            b.addLocations(latLon(p[0], p[1], altMeters));
        }
        return b.build();
    }

    /** Wrap a {@link LocationList} in the FOV oneof carrier. */
    public static LocationOrRangeBearing fovPolygon(LocationList polygon) {
        return LocationOrRangeBearing.newBuilder().setLocationList(polygon).build();
    }

    // ----- Message-type factories -----------------------------------------

    /** Minimum-valid Registration. Fills all mandatory arrays with one entry each. */
    public static SapientMessage registration(String nodeId,
                                              Registration.NodeType nodeType,
                                              String name) {
        Registration reg = Registration.newBuilder()
                .setIcdVersion("BSI Flex 335 v2.0")
                .setName(name == null ? "sapient-harness-java" : name)
                .setShortName("SHJ")
                .addNodeDefinition(Registration.NodeDefinition.newBuilder()
                        .setNodeType(nodeType).build())
                .addCapabilities(Registration.Capability.newBuilder()
                        .setCategory("Sensor")
                        .setType("Test bench simulated sensor")
                        .setValue("1")
                        .setUnits("count")
                        .build())
                .setStatusDefinition(Registration.StatusDefinition.newBuilder()
                        .setStatusInterval(Registration.Duration.newBuilder()
                                .setUnits(Registration.TimeUnits.TIME_UNITS_SECONDS)
                                .setValue(5.0f)
                                .build())
                        .build())
                .addModeDefinition(Registration.ModeDefinition.newBuilder()
                        .setModeName("default")
                        .setModeType(Registration.ModeType.MODE_TYPE_PERMANENT)
                        .build())
                .addConfigData(Registration.ConfigurationData.newBuilder()
                        .setManufacturer("mdudel")
                        .setModel("sapient-harness-java")
                        .setSerialNumber("0.1.0-SNAPSHOT")
                        .build())
                .build();
        return SapientMessage.newBuilder()
                .setTimestamp(now())
                .setNodeId(nodeId)
                .setRegistration(reg)
                .build();
    }

    /** Minimum-valid RegistrationAck. */
    public static SapientMessage registrationAck(String nodeId, String destinationId,
                                                 boolean accept, String reason) {
        RegistrationAck.Builder ack = RegistrationAck.newBuilder().setAcceptance(accept);
        if (!accept && reason != null && !reason.isBlank()) {
            ack.addAckResponseReason(reason);
        }
        SapientMessage.Builder b = SapientMessage.newBuilder()
                .setTimestamp(now())
                .setNodeId(nodeId)
                .setRegistrationAck(ack.build());
        if (destinationId != null && !destinationId.isBlank()) {
            b.setDestinationId(destinationId);
        }
        return b.build();
    }

    /** Minimum-valid StatusReport with optional lat/lon location. */
    public static SapientMessage statusReport(String nodeId,
                                              StatusReport.System system,
                                              String mode,
                                              Double latDeg, Double lonDeg, Double altMeters,
                                              StatusReport.PowerSource powerSource,
                                              StatusReport.PowerStatus powerStatus,
                                              Integer batteryLevel) {
        return statusReport(nodeId, system, StatusReport.Info.INFO_NEW, mode,
                latDeg, lonDeg, altMeters,
                powerSource, powerStatus, batteryLevel,
                null);
    }

    /**
     * StatusReport overload with an explicit {@link StatusReport.Info} flag
     * and an optional {@code fieldOfView}. The sensor generator uses this so
     * it can send {@code INFO_NEW} on the first tick and {@code INFO_UNCHANGED}
     * thereafter (per SAPIENT semantics — receivers use Info to know when to
     * re-render vs. treat as a heartbeat), and to attach a live-updating FOV
     * cone or polygon on every heartbeat.
     *
     * <p>Delegates to the 13-arg overload with empty coverage/obscuration
     * lists, matching the singleton-FOV semantics this method has always had.
     */
    public static SapientMessage statusReport(String nodeId,
                                              StatusReport.System system,
                                              StatusReport.Info info,
                                              String mode,
                                              Double latDeg, Double lonDeg, Double altMeters,
                                              StatusReport.PowerSource powerSource,
                                              StatusReport.PowerStatus powerStatus,
                                              Integer batteryLevel,
                                              LocationOrRangeBearing fieldOfView) {
        return statusReport(nodeId, system, info, mode,
                latDeg, lonDeg, altMeters,
                powerSource, powerStatus, batteryLevel,
                fieldOfView,
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList());
    }

    /**
     * Full StatusReport builder, including {@code coverage[]} and
     * {@code obscuration[]} repeated slots from the SAPIENT BSI Flex 335
     * v2.0 spec (see {@code status_report.proto} L30/33 — both are
     * {@code repeated LocationOrRangeBearing}).
     *
     * <p>Semantics:
     * <ul>
     *   <li>{@code fieldOfView} — the live look direction (typically a
     *       swept cone footprint that changes every tick).</li>
     *   <li>{@code coverage} — the sensor's reachable envelope; typically
     *       static, often a full-disc polygon. Multiple entries allowed for
     *       Fusion Nodes that report combined child coverage.</li>
     *   <li>{@code obscuration} — masked areas the sensor cannot see
     *       (buildings, terrain, EMI dead zones). Typically world-anchored;
     *       any number of entries allowed.</li>
     * </ul>
     *
     * <p>Null lists are treated as empty. Empty lists leave the wire slot
     * absent entirely (no zero-length polygon leaks). Individual null
     * elements inside a non-null list are skipped defensively.
     */
    public static SapientMessage statusReport(String nodeId,
                                              StatusReport.System system,
                                              StatusReport.Info info,
                                              String mode,
                                              Double latDeg, Double lonDeg, Double altMeters,
                                              StatusReport.PowerSource powerSource,
                                              StatusReport.PowerStatus powerStatus,
                                              Integer batteryLevel,
                                              LocationOrRangeBearing fieldOfView,
                                              List<LocationOrRangeBearing> coverage,
                                              List<LocationOrRangeBearing> obscuration) {
        StatusReport.Builder sr = StatusReport.newBuilder()
                .setReportId(newUlid())
                .setSystem(system == null ? StatusReport.System.SYSTEM_OK : system)
                .setInfo(info == null ? StatusReport.Info.INFO_NEW : info)
                .setMode(mode == null ? "default" : mode);
        if (latDeg != null && lonDeg != null) {
            sr.setNodeLocation(latLon(latDeg, lonDeg, altMeters));
        }
        if (powerSource != null || powerStatus != null || batteryLevel != null) {
            StatusReport.Power.Builder p = StatusReport.Power.newBuilder();
            if (powerSource != null) p.setSource(powerSource);
            if (powerStatus != null) p.setStatus(powerStatus);
            if (batteryLevel != null) p.setLevel(batteryLevel);
            sr.setPower(p.build());
        }
        if (fieldOfView != null) {
            sr.setFieldOfView(fieldOfView);
        }
        if (coverage != null) {
            for (LocationOrRangeBearing c : coverage) {
                if (c != null) sr.addCoverage(c);
            }
        }
        if (obscuration != null) {
            for (LocationOrRangeBearing o : obscuration) {
                if (o != null) sr.addObscuration(o);
            }
        }
        return SapientMessage.newBuilder()
                .setTimestamp(now())
                .setNodeId(nodeId)
                .setStatusReport(sr.build())
                .build();
    }

    /**
     * Detection report for a single object at {@code (lat, lon)} with the
     * given stable object_id (so consecutive reports represent the same track).
     */
    public static SapientMessage detectionReport(String nodeId,
                                                 String objectId,
                                                 double latDeg, double lonDeg,
                                                 Double altMeters,
                                                 float confidence,
                                                 String classification) {
        DetectionReport.Builder dr = DetectionReport.newBuilder()
                .setReportId(newUlid())
                .setObjectId(objectId)
                .setLocation(latLon(latDeg, lonDeg, altMeters))
                .setDetectionConfidence(confidence);
        if (classification != null && !classification.isBlank()) {
            dr.addClassification(DetectionReport.DetectionReportClassification.newBuilder()
                    .setType(classification)
                    .setConfidence(confidence)
                    .build());
        }
        return SapientMessage.newBuilder()
                .setTimestamp(now())
                .setNodeId(nodeId)
                .setDetectionReport(dr.build())
                .build();
    }

    /** Minimum-valid Task. */
    public static SapientMessage task(String nodeId, String destinationId,
                                      String taskName, Task.Control control,
                                      String taskDescription) {
        Task.Builder t = Task.newBuilder()
                .setTaskId(newUlid())
                .setControl(control == null ? Task.Control.CONTROL_START : control);
        if (taskName != null && !taskName.isBlank()) t.setTaskName(taskName);
        if (taskDescription != null && !taskDescription.isBlank()) {
            t.setTaskDescription(taskDescription);
        }
        SapientMessage.Builder b = SapientMessage.newBuilder()
                .setTimestamp(now())
                .setNodeId(nodeId)
                .setTask(t.build());
        if (destinationId != null && !destinationId.isBlank()) {
            b.setDestinationId(destinationId);
        }
        return b.build();
    }

    /** Minimum-valid TaskAck. */
    public static SapientMessage taskAck(String nodeId, String destinationId,
                                         String taskId, TaskAck.TaskStatus status,
                                         List<String> reasons) {
        TaskAck.Builder ack = TaskAck.newBuilder()
                .setTaskId(taskId == null ? newUlid() : taskId)
                .setTaskStatus(status == null ? TaskAck.TaskStatus.TASK_STATUS_ACCEPTED : status);
        if (reasons != null) reasons.forEach(ack::addReason);
        SapientMessage.Builder b = SapientMessage.newBuilder()
                .setTimestamp(now())
                .setNodeId(nodeId)
                .setTaskAck(ack.build());
        if (destinationId != null && !destinationId.isBlank()) {
            b.setDestinationId(destinationId);
        }
        return b.build();
    }

    /** Minimum-valid Alert with optional lat/lon location. */
    public static SapientMessage alert(String nodeId,
                                       Alert.AlertType alertType,
                                       Alert.AlertStatus alertStatus,
                                       Alert.DiscretePriority priority,
                                       String description,
                                       Double latDeg, Double lonDeg, Double altMeters) {
        Alert.Builder a = Alert.newBuilder()
                .setAlertId(newUlid())
                .setAlertType(alertType == null
                        ? Alert.AlertType.ALERT_TYPE_INFORMATION
                        : alertType)
                .setStatus(alertStatus == null
                        ? Alert.AlertStatus.ALERT_STATUS_ACTIVE
                        : alertStatus)
                .setPriority(priority == null
                        ? Alert.DiscretePriority.DISCRETE_PRIORITY_MEDIUM
                        : priority);
        if (description != null && !description.isBlank()) {
            a.setDescription(description);
        }
        if (latDeg != null && lonDeg != null) {
            a.setLocation(latLon(latDeg, lonDeg, altMeters));
        }
        return SapientMessage.newBuilder()
                .setTimestamp(now())
                .setNodeId(nodeId)
                .setAlert(a.build())
                .build();
    }

    /** Minimum-valid AlertAck. */
    public static SapientMessage alertAck(String nodeId, String destinationId,
                                          String alertId,
                                          AlertAck.AlertAckStatus status,
                                          List<String> reasons) {
        AlertAck.Builder ack = AlertAck.newBuilder()
                .setAlertId(alertId == null ? newUlid() : alertId)
                .setAlertAckStatus(status == null
                        ? AlertAck.AlertAckStatus.ALERT_ACK_STATUS_ACCEPTED
                        : status);
        if (reasons != null) reasons.forEach(ack::addReason);
        SapientMessage.Builder b = SapientMessage.newBuilder()
                .setTimestamp(now())
                .setNodeId(nodeId)
                .setAlertAck(ack.build());
        if (destinationId != null && !destinationId.isBlank()) {
            b.setDestinationId(destinationId);
        }
        return b.build();
    }

    /** Minimum-valid Error message. */
    public static SapientMessage error(String nodeId, String errorMessage) {
        uk.gov.dstl.sapientmsg.bsiflex335v2.Error.Builder e =
                uk.gov.dstl.sapientmsg.bsiflex335v2.Error.newBuilder()
                        .setPacket(ByteString.EMPTY)
                        .addErrorMessage(errorMessage == null || errorMessage.isBlank()
                                ? "unspecified error"
                                : errorMessage);
        return SapientMessage.newBuilder()
                .setTimestamp(now())
                .setNodeId(nodeId)
                .setError(e.build())
                .build();
    }
}
