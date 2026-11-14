package com.mordor.kelly.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debian / jpackage --icon 要独立 PNG，不要和 Windows 的 .ico 混在同一目录。
 */
class LinuxIconFormatTest {

    @Test
    void jpackageIconIsSquarePngAtLeast256() throws IOException {
        Path png = Path.of("src/main/jpackage/linux/kelly.png");
        assertTrue(Files.exists(png), "missing " + png);
        byte[] data = Files.readAllBytes(png);
        assertTrue(data.length >= 24, "too small to be PNG");
        assertEquals((byte) 0x89, data[0]);
        assertEquals('P', data[1]);
        assertEquals('N', data[2]);
        assertEquals('G', data[3]);
        ByteBuffer buf = ByteBuffer.wrap(data, 16, 8).order(ByteOrder.BIG_ENDIAN);
        int width = buf.getInt();
        int height = buf.getInt();
        assertEquals(width, height, "debian icon must be square");
        assertTrue(width >= 256, "debian launcher icon should be at least 256px, got " + width);
    }

    @Test
    void pomLinuxProfilePointsAtLinuxPng() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("jpackage-linux-deb"));
        assertTrue(pom.contains("src/main/jpackage/linux/kelly.png"));
    }
}
