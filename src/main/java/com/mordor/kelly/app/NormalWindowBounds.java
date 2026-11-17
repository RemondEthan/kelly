package com.mordor.kelly.app;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.stage.Stage;

/** 保存普通状态的内容尺寸，避免把最大化后的尺寸用作还原尺寸。 */
final class NormalWindowBounds {
    record Bounds(double x, double y, double width, double height) {}

    private final Stage stage;
    private Bounds bounds;
    private boolean pending;

    NormalWindowBounds(Stage stage) {
        this.stage = stage;
        capture();
        InvalidationListener changed = ignored -> {
            if (pending) return;
            pending = true;
            // 合并同一次窗口状态切换中的位置、尺寸和最大化通知。
            Platform.runLater(() -> {
                pending = false;
                capture();
            });
        };
        stage.xProperty().addListener(changed);
        stage.yProperty().addListener(changed);
        stage.getScene().widthProperty().addListener(changed);
        stage.getScene().heightProperty().addListener(changed);
        stage.maximizedProperty().addListener(changed);
        stage.iconifiedProperty().addListener(changed);
    }

    Bounds get() {
        capture();
        return bounds;
    }

    private void capture() {
        if (stage.isShowing() && !stage.isMaximized() && !stage.isIconified()
                && !stage.isFullScreen()) {
            bounds = new Bounds(stage.getX(), stage.getY(),
                    stage.getScene().getWidth(), stage.getScene().getHeight());
        }
    }
}
