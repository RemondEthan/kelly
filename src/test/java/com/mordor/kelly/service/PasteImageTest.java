package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasteImageTest {

    @TempDir Path tmp;

    @Test
    void prefersRawImageOverText() {
        byte[] png = {1, 2, 3};
        Optional<PasteImage.Accepted> got = PasteImage.resolve(Optional.of(png), List.of(), true);
        assertTrue(got.isPresent());
        assertArrayEquals(png, got.get().bytes());
        assertEquals("image/png", got.get().mime());
    }

    @Test
    void readsImageFileWhenNoRawImage() throws Exception {
        Path file = tmp.resolve("shot.jpg");
        Files.write(file, new byte[]{9, 9, 9});
        Optional<PasteImage.Accepted> got = PasteImage.resolve(Optional.empty(), List.of(file), false);
        assertTrue(got.isPresent());
        assertArrayEquals(new byte[]{9, 9, 9}, got.get().bytes());
        assertEquals("image/jpeg", got.get().mime());
    }

    @Test
    void ignoresNonImageFiles() throws Exception {
        Path file = tmp.resolve("note.txt");
        Files.writeString(file, "hi");
        assertTrue(PasteImage.resolve(Optional.empty(), List.of(file), true).isEmpty());
    }

    @Test
    void rejectsOversize() {
        assertTrue(PasteImage.resolve(Optional.of(new byte[ImageWire.MAX_ORIGINAL_BYTES + 1]),
                List.of(), false).isEmpty());
    }
}
