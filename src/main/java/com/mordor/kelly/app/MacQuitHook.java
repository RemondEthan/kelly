package com.mordor.kelly.app;

import com.sun.glass.ui.Application;

/**
 * macOS 退出钩子——接管 Glass 层的系统退出事件。
 *
 * <p>在 macOS 上，系统菜单的 ⌘Q 和 Dock 右键"退出"不会触发
 * JavaFX Scene 的 accelerator，而是由 Glass（JavaFX 底层渲染引擎）的
 * {@code handleQuitAction} 事件处理。
 *
 * <p>当设置了 {@code Platform.setImplicitExit(false)} 后，
 * Glass 对 handleQuitAction 的默认处理几乎是空操作，必须自己接管。
 *
 * <p>此事件处理器通过 {@link Application#setEventHandler} 注册，
 * 覆盖所有 Glass 应用事件。除了 handleQuitAction 和 handleDidUnhideAction
 * 外，其他事件都委托给原始处理器（prev），保持默认行为。
 *
 * <p>事件说明：
 * <ul>
 *   <li>{@code handleQuitAction} — 用户通过 Cmd+Q 或 Dock"退出"触发退出</li>
 *   <li>{@code handleDidUnhideAction} — 用户点击 Dock 图标取消隐藏应用，
 *       此时恢复窗口（注意：不是 handleDidBecomeActiveAction，因为系统通知、
 *       权限弹窗等也会触发 becomeActive，会错误地把已隐藏的窗口拉出来）</li>
 *   <li>其他事件 — 委托给原始处理器保持默认行为</li>
 * </ul>
 */
final class MacQuitHook {

    private MacQuitHook() {}

    /**
     * 安装 macOS Glass 层退出钩子。
     *
     * <p>获取当前 Glass Application 实例，保存原始事件处理器，
     * 然后设置新的 EventHandler 覆盖退出和取消隐藏事件。
     *
     * @param onQuit 退出回调（Cmd+Q 或 Dock"退出"时触发）
     * @param onShow 恢复窗口回调（点击 Dock 图标取消隐藏时触发）
     */
    static void install(Runnable onQuit, Runnable onShow) {
        try {
            Application glass = Application.GetApplication();
            if (glass == null) return;
            // 保存原始事件处理器，其他事件委托给它
            Application.EventHandler prev = glass.getEventHandler();
            glass.setEventHandler(new Application.EventHandler() {
            /** Cmd+Q 或 Dock「退出」：触发退出流程 */
            @Override
            public void handleQuitAction(Application app, long time) {
                onQuit.run();
            }

            /** 应用即将完成启动 */
            @Override
            public void handleWillFinishLaunchingAction(Application app, long time) {
                if (prev != null) prev.handleWillFinishLaunchingAction(app, time);
            }

            /** 应用已完成启动 */
            @Override
            public void handleDidFinishLaunchingAction(Application app, long time) {
                if (prev != null) prev.handleDidFinishLaunchingAction(app, time);
            }

            /** 应用即将变为活跃状态 */
            @Override
            public void handleWillBecomeActiveAction(Application app, long time) {
                if (prev != null) prev.handleWillBecomeActiveAction(app, time);
            }

            /**
             * 应用已变为活跃状态。
             *
             * 注意：不在此处调 onShow！handleDidBecomeActiveAction 在系统事件
             * （通知、权限弹窗等）让 app 变 active 时也会触发，
             * 会错误地把已隐藏的窗口拉出来。
             * 只在 handleDidUnhideAction（真正取消隐藏）里恢复窗口。
             */
            @Override
            public void handleDidBecomeActiveAction(Application app, long time) {
                if (prev != null) prev.handleDidBecomeActiveAction(app, time);
            }

            /** 应用即将失去活跃状态 */
            @Override
            public void handleWillResignActiveAction(Application app, long time) {
                if (prev != null) prev.handleWillResignActiveAction(app, time);
            }

            /** 应用已失去活跃状态 */
            @Override
            public void handleDidResignActiveAction(Application app, long time) {
                if (prev != null) prev.handleDidResignActiveAction(app, time);
            }

            /** 系统内存不足警告 */
            @Override
            public void handleDidReceiveMemoryWarning(Application app, long time) {
                if (prev != null) prev.handleDidReceiveMemoryWarning(app, time);
            }

            /** 应用即将隐藏（最小化到 Dock） */
            @Override
            public void handleWillHideAction(Application app, long time) {
                if (prev != null) prev.handleWillHideAction(app, time);
            }

            /** 应用已隐藏 */
            @Override
            public void handleDidHideAction(Application app, long time) {
                if (prev != null) prev.handleDidHideAction(app, time);
            }

            /** 应用即将取消隐藏（从 Dock 恢复） */
            @Override
            public void handleWillUnhideAction(Application app, long time) {
                if (prev != null) prev.handleWillUnhideAction(app, time);
            }

            /**
             * 应用已取消隐藏——用户点击 Dock 图标恢复应用。
             *
             * 此处调用 onShow 恢复窗口，而不是在 handleDidBecomeActiveAction 中，
             * 因为只有"取消隐藏"才是用户真正想看到窗口的意图。
             */
            @Override
            public void handleDidUnhideAction(Application app, long time) {
                if (prev != null) prev.handleDidUnhideAction(app, time);
                if (onShow != null) {
                    onShow.run();
                }
            }

            /** 系统请求打开文件 */
            @Override
            public void handleOpenFilesAction(Application app, long time, String[] files) {
                if (prev != null) prev.handleOpenFilesAction(app, time, files);
            }

            /** 主题变更（深色/浅色模式切换） */
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
