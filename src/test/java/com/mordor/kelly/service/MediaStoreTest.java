package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaStoreTest {

    @TempDir Path tmp;

    @Test
    void writesPreviewAndOriginalUnderImCodeHash() throws Exception {
        MediaStore store = new MediaStore(tmp);
        byte[] orig = {1, 2, 3, 4};
        byte[] jpeg = {5, 6};
        MediaStore.Stored saved = store.save("ROOM", "mid", orig, "image/png", jpeg);
        assertEquals("mid.preview.jpg", saved.previewRel());
        assertEquals("mid.png", saved.originalRel());
        assertArrayEquals(orig, Files.readAllBytes(store.resolve("ROOM", saved.originalRel())));
        assertArrayEquals(jpeg, Files.readAllBytes(store.resolve("ROOM", saved.previewRel())));
        assertTrue(store.dir("ROOM").toString().contains(ChatHistory.sha256Hex("ROOM")));
    }

    @Test
    void jpegOriginalKeepsJpgSuffix() throws Exception {
        MediaStore store = new MediaStore(tmp);
        MediaStore.Stored saved = store.save("ROOM", "x", new byte[]{1}, "image/jpeg", new byte[]{2});
        assertEquals("x.jpg", saved.originalRel());
    }
}
