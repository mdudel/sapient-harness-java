/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.wire;

/**
 * SAPIENT wire framing constants — matches dstl reference implementation exactly.
 *
 * <p>Wire format:
 * <pre>
 * ┌──────────────────┬─────────────────────────────────┐
 * │  4-byte length   │   protobuf-encoded SapientMessage│
 * │  (little-endian) │   (that length in bytes)         │
 * └──────────────────┴─────────────────────────────────┘
 * </pre>
 *
 * <p>Reference: dstl/BSI-Flex-335-v2-Test-Harness
 * {@code SAPIENTMessageProcessor/ByteDataMessageBuilder.cs} — see {@code AddHeader}
 * and {@code ProcessReceivedData}. Uses {@code BitConverter.GetBytes(dataLength)}
 * with an explicit reverse on big-endian hosts, i.e. always little-endian on the wire.
 *
 * <p>No sync marker, no delimiter, no null termination. The 4-byte length field is
 * an unsigned int32 (in practice always well under 2 GiB).
 *
 * <p>Note: dstl's legacy {@code sendNullTermination} option in the C# Data Agent
 * config is NOT this framing — that flag relates to a legacy XML-over-TCP path
 * and does not apply to protobuf SAPIENT messages.
 */
public final class SapientFrameCodec {

    /** Length of the length-prefix header in bytes. */
    public static final int HEADER_LENGTH = 4;

    /** Offset of the length field in the header. */
    public static final int LENGTH_FIELD_OFFSET = 0;

    /** Length of the length field itself in bytes. */
    public static final int LENGTH_FIELD_LENGTH = 4;

    /**
     * Practical maximum message size in bytes. dstl's implementation uses
     * {@code SocketCommsCommon.MaximumPacketSize} (~10 MiB in the reference).
     * Set generously to avoid rejecting legitimate large Detection Reports.
     */
    public static final int MAX_FRAME_LENGTH = 16 * 1024 * 1024; // 16 MiB

    private SapientFrameCodec() {
        // utility
    }
}
