package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageAssemblerTest {

    @Test
    void completesOutOfOrder() {
        byte[] original = new byte[ImageWire.CHUNK_BYTES + 8];
        original[0] = 7;
        original[original.length - 1] = 9;
        List<byte[]> parts = ImageWire.split(original);
        ImageAssembler asm = new ImageAssembler(parts.size());
        assertFalse(asm.offer(1, parts.get(1)));
        assertTrue(asm.offer(0, parts.get(0)));
        assertArrayEquals(original, asm.bytes());
    }

    @Test
    void failsOnBadIndex() {
        ImageAssembler asm = new ImageAssembler(1);
        assertThrows(IllegalArgumentException.class, () -> asm.offer(3, new byte[]{1}));
    }
}
