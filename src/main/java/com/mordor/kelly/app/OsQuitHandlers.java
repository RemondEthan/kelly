package com.mordor.kelly.app;

import java.awt.Desktop;
import java.awt.desktop.AppReopenedListener;

/**
 * 跨平台退出事件处理器——统一注册 macOS 和 AWT 层的退出事件。
 *
 * <p>此组件注册两层退出事件处理器，确保所有平台的退出路径都被覆盖：
 *
 * <ul>
 *   <li><b>Glass 层</b>（{@link MacQuitHook}）：处理 macOS 的 Cmd+Q 和 Dock"退出"，
 *       这些事件由 JavaFX Glass 引擎直接处理，不会进入 Scene accelerator</li>
 *   <li><b>AWT Desktop 层</b>：处理 macOS 的系统级退出请求（APP_QUIT_HANDLER）
 *       和 Dock 图标点击重开事件（APP_EVENT_REOPENED）</li>
 * </ul>
 *
 * <p>为什么需要两层：
 * macOS 的退出事件可以通过多个路径到达应用——Glass 的 handleQuitAction
 * 和 AWT Desktop 的 APP_QUIT_HANDLER 可能都会触发。两层都注册可以确保
 * 无论哪条路径先到达，都能正确执行退出流程。
 *
 * <p>AWT Desktop 操作在 AWT 线程中执行（通过 {@link AwtSupport#run}），
 * 避免在 JavaFX 线程上直接操作 AWT API。
 */
public final class OsQuitHandlers {

    private OsQuitHandlers() {}

    /**
     * 安装所有平台的退出事件处理器。
     *
     * <p>注册 Glass 层和 AWT Desktop 层的退出事件处理器。
     * AWT 操作通过 {@link AwtSupport#run} 在 AWT 线程中执行。
     *
     * @param onQuit 退出回调（QuitManager::quit）
     * @param onShow 恢复窗口回调（FxStageSupport::show）
     */
    public static void install(Runnable onQuit, Runnable onShow) {
        // Glass 层：处理 macOS Cmd+Q / Dock 退出
        MacQuitHook.install(onQuit, onShow);
        // AWT Desktop 层：处理系统级退出请求和 Dock 重开事件
        AwtSupport.run(() -> {
            try {
                if (!Desktop.isDesktopSupported()) {
                    return;
                }
                Desktop desktop = Desktop.getDesktop();
                // 注册退出处理器：系统发送 APP_QUIT 事件时触发
                if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                    desktop.setQuitHandler((e, response) -> {
                        onQuit.run();
                        response.performQuit(); // 确认退出
                    });
                }
                // 注册 Dock 图标重开监听器：用户点击 Dock 图标时触发（窗口已隐藏时）
                desktop.addAppEventListener((AppReopenedListener) e -> onShow.run());
            } catch (Throwable t) {
                System.err.println("[Quit] Desktop hook 安装失败: " + t.getMessage());
            }
        });
    }
}
