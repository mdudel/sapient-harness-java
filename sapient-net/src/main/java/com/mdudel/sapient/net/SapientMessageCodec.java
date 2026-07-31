/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import com.mdudel.sapient.core.wire.SapientFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.nio.ByteOrder;
import java.util.List;

/**
 * Netty inbound/outbound codec pair for the SAPIENT wire format.
 *
 * <p>Inbound (reads from socket):
 * <ol>
 *   <li>{@link LengthFieldBasedFrameDecoder} reads the 4-byte little-endian length
 *       prefix and delivers exactly one framed payload as a {@link ByteBuf}.</li>
 *   <li>{@link Decoder} parses the payload as a {@link SapientMessage} protobuf.</li>
 * </ol>
 *
 * <p>Outbound (writes to socket): {@link Encoder} serialises a {@link SapientMessage}
 * to its protobuf bytes and prepends the 4-byte little-endian length header.
 *
 * <p>Framing constants live in {@link SapientFrameCodec} and match dstl's
 * {@code ByteDataMessageBuilder.cs} exactly (see class-level docs there).
 */
public final class SapientMessageCodec {

    private SapientMessageCodec() {
        // utility container
    }

    /**
     * A framed-length decoder configured for SAPIENT: 4-byte little-endian length
     * prefix, length excludes the header itself, and the decoder strips the header
     * before delivering the payload downstream.
     */
    public static LengthFieldBasedFrameDecoder newFrameDecoder() {
        return new LengthFieldBasedFrameDecoder(
                ByteOrder.LITTLE_ENDIAN,
                SapientFrameCodec.MAX_FRAME_LENGTH,
                SapientFrameCodec.LENGTH_FIELD_OFFSET,
                SapientFrameCodec.LENGTH_FIELD_LENGTH,
                /* lengthAdjustment */ 0,
                /* initialBytesToStrip */ SapientFrameCodec.HEADER_LENGTH,
                /* failFast */ true);
    }

    /** Parses a framed payload (produced by {@link #newFrameDecoder()}) as a SapientMessage. */
    public static final class Decoder
            extends io.netty.handler.codec.MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
                throws Exception {
            byte[] bytes = new byte[in.readableBytes()];
            in.readBytes(bytes);
            SapientMessage msg = SapientMessage.parseFrom(bytes);
            out.add(msg);
        }
    }

    /** Serialises a SapientMessage and prepends the 4-byte little-endian length header. */
    public static final class Encoder
            extends io.netty.handler.codec.MessageToByteEncoder<SapientMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, SapientMessage msg, ByteBuf out) {
            byte[] payload = msg.toByteArray();
            out.order(ByteOrder.LITTLE_ENDIAN).writeIntLE(payload.length);
            out.writeBytes(payload);
        }
    }

    /**
     * Convenience: build a full framed byte array for a message (useful for tests
     * and for callers not using Netty pipelines directly).
     */
    public static ByteBuf frame(SapientMessage msg) {
        byte[] payload = msg.toByteArray();
        ByteBuf buf = Unpooled.buffer(SapientFrameCodec.HEADER_LENGTH + payload.length);
        buf.writeIntLE(payload.length);
        buf.writeBytes(payload);
        return buf;
    }

    /** Convenience: encode a {@link SapientMessage} to a length-framed byte array. */
    public static byte[] frameToBytes(SapientMessage msg) {
        ByteBuf buf = frame(msg);
        try {
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }
}
