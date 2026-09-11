package com.mordor.kelly.app;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

/**
 * Windows 原生任务栏按钮闪烁：通过 user32 FlashWindowEx。
 * <p>
 * 非 Windows 平台所有方法静默无操作,UnreadAlert 不分平台调用同一套代码。
 * 取 HWND 走 Stage.impl_getPeer() -> PlatformWindow.getNativeHandle() 反射链,
 * 反射失败(Headless / Stage 已 dispose / 内部 API 变动)一律返回 0。
 */
final class WinFlash {

    static final int FLASHW_STOP = 0x00000000;
    static final int FLASHW_TRAY = 0x00000002;
    static final int FLASHW_TIMERNOFG = 0x0000000C;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    private WinFlash() {}

    static boolean supported() {
        return WINDOWS;
    }

    static void start(long hwnd) {
        if (!WINDOWS || hwnd == 0) {
            return;
        }
        flash(hwnd, FLASHW_TRAY | FLASHW_TIMERNOFG);
    }

    static void stop(long hwnd) {
        if (!WINDOWS || hwnd == 0) {
            return;
        }
        flash(hwnd, FLASHW_STOP);
    }

    static long hwndOf(Object stage) {
        if (!WINDOWS || stage == null) {
            return 0L;
        }
        try {
            Object tkStage = stage.getClass().getMethod("impl_getPeer").invoke(stage);
            Object platformWindow = tkStage.getClass().getMethod("getPlatformWindow").invoke(tkStage);
            return (long) platformWindow.getClass().getMethod("getNativeHandle").invoke(platformWindow);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void flash(long hwnd, int flags) {
        WinUser.FLASHWINFO info = new WinUser.FLASHWINFO();
        info.cbSize = info.size();
        info.hWnd = new WinDef.HWND(Pointer.createConstant(hwnd));
        info.dwFlags = flags;
        info.uCount = 0;
        info.dwTimeout = 0;
        User32.INSTANCE.FlashWindowEx(info);
    }
}
