package com.mordor.kelly.app;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

/**
 * Windows 原生任务栏按钮闪烁——通过 JNA 调用 user32 FlashWindowEx API。
 *
 * <p>Windows 任务栏按钮闪烁是系统级功能，Java/JavaFX 无法直接实现，
 * 需要通过 JNA（Java Native Access）调用 Windows API。
 *
 * <p>跨平台兼容：
 * 非 Windows 平台上所有方法静默无操作（通过 {@link #WINDOWS} 标志判断），
 * 这样 {@link UnreadAlert} 可以不分平台调用同一套代码。
 *
 * <p>HWND 获取：
 * 通过反射链 {@code Stage.impl_getPeer()} -> {@code PlatformWindow.getNativeHandle()}
 * 获取 JavaFX Stage 的原生窗口句柄。反射失败（Headless 环境、Stage 已 dispose、
 * 内部 API 变动等）一律返回 0，上层调用者据此决定是否使用原生闪烁。
 *
 * <p>FlashWindowEx 标志位说明：
 * <ul>
 *   <li>{@link #FLASHW_STOP} (0x00)：停止闪烁</li>
 *   <li>{@link #FLASHW_TRAY} (0x02)：闪烁任务栏托盘图标</li>
 *   <li>{@link #FLASHW_TIMERNOFG} (0x0C)：持续闪烁直到窗口获得焦点
 *       （0x04 FLASHW_TIMER + 0x08 FLASHW_NOFG）</li>
 * </ul>
 */
final class WinFlash {

    /** 停止闪烁标志 */
    static final int FLASHW_STOP = 0x00000000;
    /** 闪烁托盘图标标志 */
    static final int FLASHW_TRAY = 0x00000002;
    /** 持续闪烁直到窗口获得焦点标志（FLASHW_TIMER | FLASHW_NOFG） */
    static final int FLASHW_TIMERNOFG = 0x0000000C;

    /** 是否为 Windows 平台 */
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    private WinFlash() {}

    /**
     * 当前平台是否支持原生闪烁。
     *
     * @return true 表示 Windows 平台
     */
    static boolean supported() {
        return WINDOWS;
    }

    /**
     * 开始闪烁任务栏按钮。
     *
     * <p>使用 FLASHW_TRAY | FLASHW_TIMERNOFG 标志：
     * 闪烁托盘图标，持续闪烁直到窗口获得焦点。
     *
     * @param hwnd 原生窗口句柄，0 表示无效
     */
    static void start(long hwnd) {
        if (!WINDOWS || hwnd == 0) {
            return;
        }
        flash(hwnd, FLASHW_TRAY | FLASHW_TIMERNOFG);
    }

    /**
     * 停止闪烁任务栏按钮。
     *
     * @param hwnd 原生窗口句柄，0 表示无效
     */
    static void stop(long hwnd) {
        if (!WINDOWS || hwnd == 0) {
            return;
        }
        flash(hwnd, FLASHW_STOP);
    }

    /**
     * 从 JavaFX Stage 获取 Windows 原生窗口句柄（HWND）。
     *
     * <p>通过反射调用 JavaFX 内部 API：
     * <ol>
     *   <li>{@code Stage.impl_getPeer()} 获取 TKStage（JavaFX 内部实现）</li>
     *   <li>{@code TKStage.getPlatformWindow()} 获取 PlatformWindow</li>
     *   <li>{@code PlatformWindow.getNativeHandle()} 获取原生 HWND</li>
     * </ol>
     *
     * <p>任何步骤失败都返回 0（而非抛异常），上层调用者据此判断是否使用原生闪烁。
     *
     * @param stage JavaFX Stage 对象
     * @return 原生窗口句柄，失败时返回 0
     */
    static long hwndOf(Object stage) {
        if (!WINDOWS || stage == null) {
            return 0L;
        }
        try {
            // 反射链：Stage -> TKStage -> PlatformWindow -> nativeHandle
            Object tkStage = stage.getClass().getMethod("impl_getPeer").invoke(stage);
            Object platformWindow = tkStage.getClass().getMethod("getPlatformWindow").invoke(tkStage);
            return (long) platformWindow.getClass().getMethod("getNativeHandle").invoke(platformWindow);
        } catch (Throwable t) {
            return 0L; // 反射失败，返回无效句柄
        }
    }

    /**
     * 执行 FlashWindowEx 调用。
     *
     * <p>构建 {@link WinUser.FLASHWINFO} 结构体，调用 user32.dll 的 FlashWindowEx。
     * uCount=0 和 dwTimeout=0 使用系统默认值。
     *
     * @param hwnd  原生窗口句柄
     * @param flags 闪烁标志（FLASHW_STOP / FLASHW_TRAY | FLASHW_TIMERNOFG 等）
     */
    private static void flash(long hwnd, int flags) {
        WinUser.FLASHWINFO info = new WinUser.FLASHWINFO();
        info.cbSize = info.size();
        info.hWnd = new WinDef.HWND(Pointer.createConstant(hwnd));
        info.dwFlags = flags;
        info.uCount = 0;      // 0 = 使用系统默认闪烁次数
        info.dwTimeout = 0;   // 0 = 使用系统默认闪烁间隔
        User32.INSTANCE.FlashWindowEx(info);
    }
}
