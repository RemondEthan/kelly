package com.mordor.kelly.app;

import com.sun.glass.ui.Application;

/**
 * 接住 macOS 系统菜单的 ⌘Q / Dock「退出」。
 *
 * JavaFX 把这次退出做成 Glass {@code handleQuitAction}，不会走进 Scene
 * accelerator。implicitExit=false 时默认处理几乎是空操作，必须自己接管。
 */
final class MacQuitHook {

    private MacQuitHook() {}

    static void install(Runnable onQuit, Runnable onShow) {
        try {
            Application glass = Application.GetApplication();
            if (glass == null) return;
            Application.EventHandler prev = glass.getEventHandler();
            glass.setEventHandler(new Application.EventHandler() {
            @Override
            public void handleQuitAction(Application app, long time) {
                onQuit.run();
            }

            @Override
            public void handleWillFinishLaunchingAction(Application app, long time) {
                if (prev != null) prev.handleWillFinishLaunchingAction(app, time);
            }

            @Override
            public void handleDidFinishLaunchingAction(Application app, long time) {
                if (prev != null) prev.handleDidFinishLaunchingAction(app, time);
            }

            @Override
            public void handleWillBecomeActiveAction(Application app, long time) {
                if (prev != null) prev.handleWillBecomeActiveAction(app, time);
            }

            @Override
            public void handleDidBecomeActiveAction(Application app, long time) {
                if (prev != null) prev.handleDidBecomeActiveAction(app, time);
                // 不在这里调 onShow：handleDidBecomeActiveAction 在系统事件（通知、
                // 权限弹窗等）让 app 变 active 时也会触发，会把已隐藏的窗口拉出来。
                // 只在 handleDidUnhideAction（真正取消隐藏）里恢复窗口。
            }

            @Override
            public void handleWillResignActiveAction(Application app, long time) {
                if (prev != null) prev.handleWillResignActiveAction(app, time);
            }

            @Override
            public void handleDidResignActiveAction(Application app, long time) {
                if (prev != null) prev.handleDidResignActiveAction(app, time);
            }

            @Override
            public void handleDidReceiveMemoryWarning(Application app, long time) {
                if (prev != null) prev.handleDidReceiveMemoryWarning(app, time);
            }

            @Override
            public void handleWillHideAction(Application app, long time) {
                if (prev != null) prev.handleWillHideAction(app, time);
            }

            @Override
            public void handleDidHideAction(Application app, long time) {
                if (prev != null) prev.handleDidHideAction(app, time);
            }

            @Override
            public void handleWillUnhideAction(Application app, long time) {
                if (prev != null) prev.handleWillUnhideAction(app, time);
            }

            @Override
            public void handleDidUnhideAction(Application app, long time) {
                if (prev != null) prev.handleDidUnhideAction(app, time);
                if (onShow != null) {
                    onShow.run();
                }
            }

            @Override
            public void handleOpenFilesAction(Application app, long time, String[] files) {
                if (prev != null) prev.handleOpenFilesAction(app, time, files);
            }

            @Override
            public boolean handleThemeChanged(String theme) {
                return prev != null && prev.handleThemeChanged(theme);
            }
        });
        } catch (Throwable t) {
            System.err.println("[Quit] Glass quit hook 安装失败: " + t);
        }
    }
}
