package com.mordor.kelly.app;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link SniPixmap} 的 ARGB32 编码与多尺寸批量编码。
 *
 * <p>SNI 面板按固定字节序解析像素：每像素 4 字节，依次 A、R、G、B。
 * 字节序错了图标会花屏/串色，且只能在真实面板上目测发现，
 * 所以这里用纯色图锁死字节排布。
 */
class SniPixmapTest {

    @Test
    void encodesArgb32ByteOrder() {
        // 不透明白色：A=255 R=255 G=0 B=0 → 红色
        BufferedImage red = solid(2, 2, 255, 255, 0, 0);
        SniPixmap pixmap = SniPixmap.encode(red, 2);

        assertEquals(2, pixmap.width);
        assertEquals(2, pixmap.height);
        assertEquals(16, pixmap.pixels.length);
        for (int i = 0; i < 4; i++) {
            int o = i * 4;
            assertEquals((byte) 0xFF, pixmap.pixels[o], "A 分量在前");
            assertEquals((byte) 0xFF, pixmap.pixels[o + 1], "R 分量第二");
            assertEquals(0, pixmap.pixels[o + 2], "G 分量第三");
            assertEquals(0, pixmap.pixels[o + 3], "B 分量最后");
        }
    }

    @Test
    void transparentPixelsEncodeToZero() {
        BufferedImage transparent = solid(1, 1, 0, 0, 0, 0);
        SniPixmap pixmap = SniPixmap.encode(transparent, 1);
        assertEquals(4, pixmap.pixels.length);
        for (byte b : pixmap.pixels) {
            assertEquals(0, b);
        }
    }

    @Test
    void encodesAllRequestedSizes() {
        BufferedImage src = solid(64, 64, 255, 0, 0, 255);
        List<SniPixmap> pixmaps = SniPixmap.encodeAll(src, 16, 22, 24);

        assertEquals(3, pixmaps.size());
        int[] expected = {16, 22, 24};
        for (int i = 0; i < expected.length; i++) {
            SniPixmap p = pixmaps.get(i);
            assertEquals(expected[i], p.width);
            assertEquals(expected[i] * expected[i] * 4, p.pixels.length);
            // 纯蓝源图：G/B 像素中 B 应为满值
            assertEquals((byte) 0xFF, p.pixels[3]);
        }
        // 红蓝通道交错防止 R/B 写反：R 应为 0
        assertEquals(0, pixmaps.get(0).pixels[1]);
    }

    @Test
    void rejectsPixelLengthMismatch() {
        boolean threw = false;
        try {
            new SniPixmap(2, 2, new byte[8]);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "像素长度不符必须快速失败");
    }

    /** 生成纯色图（ARGB 各通道独立可调） */
    private static BufferedImage solid(int w, int h, int a, int r, int g, int b) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int argb = (a << 24) | (r << 16) | (g << 8) | b;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }
}
