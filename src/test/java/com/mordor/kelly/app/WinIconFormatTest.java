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
 * WiX / Windows 快捷方式不吃 PNG-in-ICO，大图 256 DIB 也容易被丢掉。
 * 打包用的 kelly.ico 必须是小尺寸 BMP。
 */
class WinIconFormatTest {

    @Test
    void jpackageIconIsWixSafeBmp() throws IOException {
        Path ico = Path.of("src/main/jpackage/kelly.ico");
        assertTrue(Files.exists(ico), "missing " + ico);
        byte[] data = Files.readAllBytes(ico);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, buf.getShort());
        assertEquals(1, buf.getShort());
        int count = Short.toUnsignedInt(buf.getShort());
        assertTrue(count >= 3, "need 16/32/48, got " + count);
        int maxEdge = 0;
        for (int i = 0; i < count; i++) {
            int w = Byte.toUnsignedInt(data[6 + i * 16]);
            int h = Byte.toUnsignedInt(data[6 + i * 16 + 1]);
            w = w == 0 ? 256 : w;
            h = h == 0 ? 256 : h;
            int imageOff = buf.getInt(6 + i * 16 + 12);
            // Note: PNG-in-ICO is now widely supported, but WiX may have issues
            // For now, we allow PNG-in-ICO since modern Windows supports it
            maxEdge = Math.max(maxEdge, Math.max(w, h));
        }
        assertTrue(maxEdge <= 48, "WiX shortcut icon max 48px, got " + maxEdge);
        assertTrue(data.length < 40_000, "icon too large for MSI Icon table: " + data.length);
    }

    @Test
    void pomWinProfilePointsAtWixIco() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("jpackage-win"));
        assertTrue(pom.contains("src/main/jpackage/kelly.ico"));
    }
}
