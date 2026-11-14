package com.mordor.kelly.ui.theme;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;

/**
 * 主题切换按钮：在亮色 / 深色之间来回切换，并持久化到用户偏好。
 *
 * <p>按钮文案显示的是「点击之后要切换到的主题」，例如当前是亮色时
 * 按钮上写「深色」。</p>
 *
 * <p>切换动作只做两件事：给场景根节点换 {@code theme-dark} 样式类，
 * 以及把新主题写进 Preferences；JavaFX 会在下一个脉冲重刷整棵场景树的 CSS。</p>
 */
public class ThemeToggleButton extends Button {

    /** 主题读写服务 */
    private final ThemeService service;

    /**
     * 使用默认的 Preferences 存储。
     */
    public ThemeToggleButton() {
        this(new ThemeService());
    }

    /**
     * 使用指定的主题服务（便于测试注入）。
     *
     * @param service 主题服务
     */
    public ThemeToggleButton(ThemeService service) {
        this.service = service;
        getStyleClass().add("theme-toggle");
        setFocusTraversable(false);
        updateLabel(service.load());
        // 进入场景后按场景实际生效的主题刷新文案
        sceneProperty().addListener((obs, old, scene) ->
                updateLabel(scene == null
                        ? service.load()
                        : ThemeService.currentOf(scene.getRoot())));
        setOnAction(e -> toggle());
    }

    /**
     * 切换当前场景的主题。
     */
    private void toggle() {
        Scene scene = getScene();
        if (scene == null) {
            return;  // 还没进场景，无法定位根节点
        }
        Node root = scene.getRoot();
        AppTheme next = ThemeService.currentOf(root).toggled();
        service.save(next);
        ThemeService.apply(root, next);
        updateLabel(next);
    }

    /**
     * 刷新按钮文案：显示点击后要切换到的主题。
     *
     * @param current 当前主题
     */
    private void updateLabel(AppTheme current) {
        setText(current.toggled().label());
    }
}
