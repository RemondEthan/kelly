package com.mordor.kelly.app;

import org.junit.jupiter.api.Test;

import java.awt.Image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppIconsTest {

    @Test
    void loadsAwtPngForTaskbar() {
        Image image = AppIcons.awtImage("/icons/kelly.png");
        assertNotNull(image);
        assertTrue(image.getWidth(null) >= 32);
        assertTrue(image.getHeight(null) >= 32);
    }

    @Test
    void stageIconSizesCoverTaskbar() {
        assertEquals(5, AppIcons.STAGE_SIZES.length);
        assertEquals(16, AppIcons.STAGE_SIZES[0]);
        assertEquals(24, AppIcons.STAGE_SIZES[1]);
        assertEquals(32, AppIcons.STAGE_SIZES[2]);
        assertFalse(java.util.Arrays.stream(AppIcons.STAGE_SIZES).noneMatch(s -> s == 32));
        assertFalse(java.util.Arrays.stream(AppIcons.STAGE_SIZES).noneMatch(s -> s == 24));
    }

    @Test
    void fitAwtScalesToTraySizedSquare() {
        java.awt.Image src = AppIcons.awtImage("/icons/kelly.png");
        assertNotNull(src);
        java.awt.Image fitted = AppIcons.fitAwt(src, 16, 16);
        assertNotNull(fitted);
        assertEquals(16, fitted.getWidth(null));
        assertEquals(16, fitted.getHeight(null));
    }

    @Test
    void applyTaskbarReturnsWithoutBlockingCaller() {
        Image image = AppIcons.awtImage("/icons/kelly.png");
        assertNotNull(image);
        long t0 = System.nanoTime();
        AppIcons.applyTaskbar(image);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 200, "applyTaskbar must hop off the caller thread, took " + ms + "ms");
    }
}
