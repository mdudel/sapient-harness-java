/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.core.wire;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity check the framing constants — these MUST match dstl's C# reference
 * ({@code ByteDataMessageBuilder.cs}) to remain wire-compatible.
 */
class SapientFrameCodecTest {

    @Test
    void headerLengthIsFourBytes() {
        // Dstl uses BitConverter.GetBytes(int) which produces 4 bytes.
        assertThat(SapientFrameCodec.HEADER_LENGTH).isEqualTo(4);
        assertThat(SapientFrameCodec.LENGTH_FIELD_LENGTH).isEqualTo(4);
        assertThat(SapientFrameCodec.LENGTH_FIELD_OFFSET).isEqualTo(0);
    }

    @Test
    void maxFrameLengthIsGenerous() {
        assertThat(SapientFrameCodec.MAX_FRAME_LENGTH).isGreaterThan(1_000_000);
    }
}
