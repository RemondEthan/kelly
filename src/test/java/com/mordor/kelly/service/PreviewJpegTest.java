package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewJpegTest {

    @Test
    void encodesDecodableJpegUnderCap() throws Exception {
        BufferedImage src = new BufferedImage(200, 80, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 200; x++) {
                src.setRGB(x, y, (x * 3 + y * 11) << 8);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(src, "png", png));
        byte[] jpeg = PreviewJpeg.encode(png.toByteArray());
        assertTrue(jpeg.length > 32);
        assertTrue(jpeg.length <= PreviewJpeg.MAX_BYTES);
        assertEquals("ffd8", Integer.toHexString((jpeg[0] & 0xff) << 8 | (jpeg[1] & 0xff)));
    }
}
