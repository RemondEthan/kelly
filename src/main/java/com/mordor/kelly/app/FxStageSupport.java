package com.mordor.kelly.app;

import com.sun.glass.ui.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.lang.reflect.Method;

/**
 * JavaFX 窗口操作的安全封装——解决跨线程调用和 macOS 平台特殊问题。
 *
 * <p>核心问题：从 AWT/AppKit 回调（如托盘菜单、系统事件）恢复 JavaFX 窗口时，
 * 不能直接调用 {@code Platform.runLater}，因为当前线程可能在原生栈中
 * （AWT-EDT 或 AppKit 线程），直接跳入 FX 线程会和 Glass（JavaFX 底层渲染引擎）死锁，
 * 或者 show() 操作被"吃掉"（无效）。
 *
 * <p>解决方案：通过 {@link #runHopped(Runnable)} 先创建新线程跳出原生栈，
 * 再通过 {@code Platform.runLater} 进入 FX 线程，确保操作安全执行。
 *
 * <p>平台差异：
 * <ul>
 *   <li>macOS：{@link #hideMacApp()} 通过反射调用 Glass 的 {@code _hide()} 方法，
 *       将整个应用收进后台（不仅是隐藏窗口），这样点击 Dock 图标才能触发
 *       unhide 事件恢复窗口</li>
 *   <li>Windows/Linux：只需隐藏 Stage 即可</li>
 * </ul>
 */
final class FxStageSupport {

    /** 是否为 macOS 平台，控制是否调用 hideMacApp */
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private FxStageSupport() {}

    /**
     * 显示并恢复窗口到前台。
     *
     * <p>如果窗口已最小化，先取消最小化；如果未显示，先 show()；
     * 最后调用 toFront() 和 requestFocus() 确保窗口在最前面并获得焦点。
     *
     * @param stage 要显示的窗口
     */
    static void show(Stage stage) {
        runHopped(() -> {
            if (stage.isIconified()) {
                stage.setIconified(false); // 取消最小化
            }
            if (!stage.isShowing()) {
                stage.show(); // 显示窗口
            }
            stage.toFront();      // 移到最前面
            stage.requestFocus(); // 获得键盘焦点
        });
    }

    /**
     * 最小化窗口。
     *
     * @param stage 要最小化的窗口
     */
    static void minimize(Stage stage) {
        runHopped(() -> {
            if (stage.isShowing()) {
                stage.setIconified(true);
            }
        });
    }

    /**
     * 隐藏窗口（仅隐藏，不退出应用）。
     *
     * <p>在 macOS 上，仅调用 {@code stage.hide()} 后应用仍占前台，
     * 点 Dock 图标不会触发 unhide/become-active 事件。
     * 因此需要额外调用 {@link #hideMacApp()} 将整个应用收进后台，
     * 下次点 Dock 图标时系统会触发 unhide 事件，再把窗口拉回来。
     *
     * @param stage 要隐藏的窗口
     */
    static void hide(Stage stage) {
        runHopped(() -> {
            if (stage.isShowing()) {
                stage.hide();
            }
            // macOS 专属：将整个应用收进后台
            // 这样点 Dock 图标会触发 handleDidUnhideAction，再把窗口拉回来
            hideMacApp();
        });
    }

    /**
     * 线程跳跃：先跳出当前线程栈，再进入 JavaFX 线程。
     *
     * <p>创建新线程（kelly-fx-hop）脱离当前调用栈，
     * 然后通过 {@code Platform.runLater} 将操作派发到 FX Application Thread。
     * 这样做是因为当前线程可能在 AWT-EDT 或 AppKit 线程的原生栈中，
     * 直接调用 FX API 会死锁。
     *
     * @param action 要在 FX 线程上执行的操作
     */
    static void runHopped(Runnable action) {
        Thread hop = new Thread(() -> Platform.runLater(action), "kelly-fx-hop");
        hop.setDaemon(true);
        hop.start();
    }

    /**
     * macOS 专属：通过反射调用 Glass 的 _hide() 方法隐藏整个应用。
     *
     * <p>仅隐藏 Stage 时，应用进程仍在前台运行，macOS 的 Dock 点击事件
     * 不会被正确触发。调用 _hide() 后，应用会真正进入后台，
     * 下次点击 Dock 图标时系统会触发 handleDidUnhideAction 事件。
     *
     * <p>此方法通过反射调用，因为 Glass 内部 API 不是公开的。
     * 如果反射失败（API 变动等），静默忽略错误。
     */
    private static void hideMacApp() {
        if (!MAC) {
            return;
        }
        try {
            Application glass = Application.GetApplication();
            if (glass == null) {
                return;
            }
            // 反射调用 Glass Application 的 _hide() 方法
            Method hide = glass.getClass().getDeclaredMethod("_hide");
            hide.setAccessible(true);
            hide.invoke(glass);
        } catch (Throwable t) {
            System.err.println("[os] mac hide app failed: " + t);
        }
    }
}
