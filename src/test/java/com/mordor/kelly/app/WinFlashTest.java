package com.mordor.kelly.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 非 Windows 平台只能验证 supported=false 与 start/stop/hwndOf 不会抛。
 * Windows 上的真实闪烁效果靠手测覆盖——junit 跑不到 user32。
 */
class WinFlashTest {

    @Test
    void supportedFalseOnNonWindows() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertEquals(win, WinFlash.supported(),
                "supported() must mirror current OS");
        if (!win) {
            assertFalse(WinFlash.supported());
        }
    }

    @Test
    void startIsNoOpOffWindows() {
        if (WinFlash.supported()) return;
        assertDoesNotThrow(() -> WinFlash.start(0L));
        assertDoesNotThrow(() -> WinFlash.start(12345L));
    }

    @Test
    void stopIsNoOpOffWindows() {
        if (WinFlash.supported()) return;
        assertDoesNotThrow(() -> WinFlash.stop(0L));
        assertDoesNotThrow(() -> WinFlash.stop(12345L));
    }

    @Test
    void hwndOfNullReturnsZero() {
        assertEquals(0L, WinFlash.hwndOf(null));
    }

    @Test
    void hwndOfNonStageReturnsZero() {
        // 任意非 Stage 对象走反射链都会失败,必须安全返回 0
        Object notAStage = "not a stage";
        assertEquals(0L, WinFlash.hwndOf(notAStage));
    }
}
