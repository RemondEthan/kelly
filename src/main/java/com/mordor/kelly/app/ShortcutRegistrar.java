package com.mordor.kelly.app;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * 跨平台全局快捷键注册。
 *
 *   ⌘W / Ctrl+W —— 最小化窗口（保持登录）
 *   ⌘Q / Ctrl+Q —— 退出程序(由 onQuit 决定具体行为)
 *
 * 通过 Scene.getAccelerators() 注册,不依赖焦点控件,TextField 输入时也能触发。
 */
public final class ShortcutRegistrar {

    private ShortcutRegistrar() {}

    public static void register(Scene scene, Runnable onMinimize, Runnable onQuit) {
        KeyCombination.Modifier mod = KeyCombination.SHORTCUT_DOWN;
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, mod),
                onMinimize);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Q, mod),
                onQuit);
    }
}
