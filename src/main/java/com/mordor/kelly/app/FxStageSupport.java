package com.mordor.kelly.app;

import com.sun.glass.ui.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.lang.reflect.Method;

/**
 * 从 AWT/AppKit 回调恢复 JavaFX 窗口时，必须先 hop 出原生栈，
 * 再进 FX 线程，否则会和 Glass 死锁或把 show() 吃掉。
 */
final class FxStageSupport {

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private FxStageSupport() {}

    static void show(Stage stage) {
        runHopped(() -> {
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            if (!stage.isShowing()) {
                stage.show();
            }
            stage.toFront();
            stage.requestFocus();
        });
    }

    static void minimize(Stage stage) {
        runHopped(() -> {
            if (stage.isShowing()) {
                stage.setIconified(true);
            }
        });
    }

    static void hide(Stage stage) {
        runHopped(() -> {
            if (stage.isShowing()) {
                stage.hide();
            }
            // 仅 hide Stage 时应用仍占前台，点 Dock 不会再发 become-active；
            // Glass 也没有 applicationShouldHandleReopen。先把应用收进后台，
            // 下次点 Dock 就会 unhide / become-active，再把窗口拉回来。
            hideMacApp();
        });
    }

    static void runHopped(Runnable action) {
        Thread hop = new Thread(() -> Platform.runLater(action), "kelly-fx-hop");
        hop.setDaemon(true);
        hop.start();
    }

    private static void hideMacApp() {
        if (!MAC) {
            return;
        }
        try {
            Application glass = Application.GetApplication();
            if (glass == null) {
                return;
            }
            Method hide = glass.getClass().getDeclaredMethod("_hide");
            hide.setAccessible(true);
            hide.invoke(glass);
        } catch (Throwable t) {
            System.err.println("[os] mac hide app failed: " + t);
        }
    }
}
