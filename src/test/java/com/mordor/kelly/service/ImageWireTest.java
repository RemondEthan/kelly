package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageWireTest {

    @Test
    void rejectsOriginalOver20Mb() {
        assertFalse(ImageWire.acceptableSize(ImageWire.MAX_ORIGINAL_BYTES + 1));
        assertTrue(ImageWire.acceptableSize(ImageWire.MAX_ORIGINAL_BYTES));
    }

    @Test
    void splitsAndJoinsIncludingOutOfOrder() {
        byte[] original = new byte[ImageWire.CHUNK_BYTES * 2 + 17];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 31);
        }
        List<byte[]> parts = ImageWire.split(original);
        assertEquals(3, parts.size());
        assertEquals(ImageWire.CHUNK_BYTES, parts.get(0).length);
        ImageAssembler asm = new ImageAssembler(parts.size());
        asm.offer(2, parts.get(2));
        asm.offer(0, parts.get(0));
        assertTrue(asm.offer(1, parts.get(1)));
        assertArrayEquals(original, asm.bytes());
    }

    @Test
    void metaRoundTrip() {
        ImageWire.Meta meta = new ImageWire.Meta(
                1, "id-9", "见图", "image/png", 4, "abcd", "AQID");
        ImageWire.Meta back = ImageWire.parseMeta(ImageWire.encodeMeta(meta));
        assertEquals(meta, back);
    }

    @Test
    void chunkRoundTrip() {
        ImageWire.Chunk chunk = new ImageWire.Chunk("id-9", 1, 3, "YmFy");
        assertEquals(chunk, ImageWire.parseChunk(ImageWire.encodeChunk(chunk)));
    }

    @Test
    void sha256MatchesKnownVector() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ImageWire.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void splitRejectsOversized() {
        assertThrows(IllegalArgumentException.class,
                () -> ImageWire.split(new byte[ImageWire.MAX_ORIGINAL_BYTES + 1]));
    }

}
