/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.template;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Load/serialise SAPIENT messages from/to JSON. dstl's canonical sample messages
 * ship as JSON files (see {@code ReadSampleSapientMessage/Default.*.json} in the
 * reference C# harness) — this loader parses them into {@link SapientMessage}
 * protobuf instances for transmission or comparison.
 *
 * <p>Supports both binary protobuf and JSON round-trips. On the wire only binary
 * protobuf is used (framed by {@link com.mdudel.sapient.core.wire.SapientFrameCodec}).
 */
public final class MessageTemplateLoader {

    private static final JsonFormat.Parser JSON_PARSER = JsonFormat.parser()
            .ignoringUnknownFields();

    private static final JsonFormat.Printer JSON_PRINTER = JsonFormat.printer()
            .includingDefaultValueFields()
            .preservingProtoFieldNames();

    private MessageTemplateLoader() {
        // utility
    }

    /** Parse a JSON string (dstl format) into a {@link SapientMessage}. */
    public static SapientMessage fromJson(String json) throws InvalidProtocolBufferException {
        SapientMessage.Builder builder = SapientMessage.newBuilder();
        JSON_PARSER.merge(json, builder);
        return builder.build();
    }

    /** Load a JSON template file (dstl format) into a {@link SapientMessage}. */
    public static SapientMessage fromJsonFile(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return fromJson(json);
    }

    /** Load a JSON template from the classpath. */
    public static SapientMessage fromJsonResource(String resourcePath) throws IOException {
        ClassLoader cl = MessageTemplateLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found on classpath: " + resourcePath);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return fromJson(json);
        }
    }

    /** Serialise a {@link SapientMessage} to JSON (dstl-compatible format). */
    public static String toJson(SapientMessage msg) throws InvalidProtocolBufferException {
        return JSON_PRINTER.print(msg);
    }
}
