package com.mordor.kelly.app;

import java.awt.Desktop;
import java.awt.desktop.AppReopenedListener;

/**
 * 接管 macOS 系统级应用事件：
 *   - Glass handleQuitAction：⌘Q / Dock「退出」
 *   - Desktop APP_QUIT_HANDLER
 *   - Desktop APP_EVENT_REOPENED：点击 Dock 图标（窗口已隐藏时）
 */
public final class OsQuitHandlers {

    private OsQuitHandlers() {}

    public static void install(Runnable onQuit, Runnable onShow) {
        MacQuitHook.install(onQuit, onShow);
        AwtSupport.run(() -> {
            try {
                if (!Desktop.isDesktopSupported()) {
                    return;
                }
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                    desktop.setQuitHandler((e, response) -> {
                        onQuit.run();
                        response.performQuit();
                    });
                }
                desktop.addAppEventListener((AppReopenedListener) e -> onShow.run());
            } catch (Throwable t) {
                System.err.println("[Quit] Desktop hook 安装失败: " + t.getMessage());
            }
        });
    }
}
