package com.mordor.kelly.app;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * 跨平台全局快捷键注册器。
 *
 * <p>使用 JavaFX 的 {@link Scene#getAccelerators()} 机制注册快捷键，
 * 不依赖焦点控件——即使光标在 TextField 输入框中也能正常触发。
 *
 * <p>快捷键映射：
 * <ul>
 *   <li>{@code Cmd+W}（macOS）/ {@code Ctrl+W}（Windows/Linux）— 最小化窗口（保持登录状态）</li>
 *   <li>{@code Cmd+Q}（macOS）/ {@code Ctrl+Q}（Windows/Linux）— 退出程序</li>
 * </ul>
 *
 * <p>{@link KeyCombination#SHORTCUT_DOWN} 修饰符自动适配平台：
 * 在 macOS 上映射为 Command 键（⌘），在 Windows/Linux 上映射为 Ctrl 键。
 * 这样一套代码就能正确处理两个平台的快捷键习惯。
 */
public final class ShortcutRegistrar {

    private ShortcutRegistrar() {}

    /**
     * 注册全局快捷键到指定场景。
     *
     * @param scene      要注册快捷键的场景
     * @param onMinimize 最小化窗口的回调
     * @param onQuit     退出程序的回调
     */
    public static void register(Scene scene, Runnable onMinimize, Runnable onQuit) {
        // SHORTCUT_DOWN：macOS 上是 ⌘，Windows/Linux 上是 Ctrl
        KeyCombination.Modifier mod = KeyCombination.SHORTCUT_DOWN;
        // Cmd+W / Ctrl+W → 最小化窗口
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, mod),
                onMinimize);
        // Cmd+Q / Ctrl+Q → 退出程序
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Q, mod),
                onQuit);
    }
}
