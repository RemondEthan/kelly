package com.mordor.kelly.app;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconPngFormatTest {

    @Test
    void runtimeAndLinuxPngHaveTransparentCanvas() throws IOException {
        assertTransparentCorner("src/main/resources/icons/kelly.png");
        assertTransparentCorner("src/main/resources/icons/kelly-alert.png");
        assertTransparentCorner("src/main/jpackage/linux/kelly.png");
    }

    private static void assertTransparentCorner(String path) throws IOException {
        BufferedImage img = ImageIO.read(Path.of(path).toFile());
        assertNotNull(img, "missing " + path);
        int w = img.getWidth();
        int h = img.getHeight();
        assertTrue(w >= 256 && h >= 256, path + " too small: " + w + "x" + h);
        int[] samples = {
                img.getRGB(0, 0),
                img.getRGB(w - 1, 0),
                img.getRGB(0, h - 1),
                img.getRGB(w - 1, h - 1)
        };
        for (int argb : samples) {
            int alpha = (argb >>> 24) & 0xff;
            assertEquals(0, alpha, path + " canvas should be transparent, got alpha " + alpha);
        }
    }
}
