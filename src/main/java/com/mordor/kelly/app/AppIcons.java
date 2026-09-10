package com.mordor.kelly.app;

import javafx.collections.ObservableList;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Taskbar;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 窗口标题栏、Windows 任务栏按钮、未读红点共用同一套图标。
 * 任务栏要 16/32，不能只丢一张 512 PNG。
 */
final class AppIcons {

    static final int[] STAGE_SIZES = {16, 32, 48, 256};

    private AppIcons() {}

    static java.awt.Image awtImage(String path) {
        try (InputStream in = AppIcons.class.getResourceAsStream(path)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    static List<Image> fxIcons(String path) {
        var url = AppIcons.class.getResource(path);
        if (url == null) {
            return List.of();
        }
        String spec = url.toExternalForm();
        List<Image> out = new ArrayList<>();
        for (int size : STAGE_SIZES) {
            out.add(new Image(spec, size, size, true, true));
        }
        return out;
    }

    static void applyStage(Stage stage, String path) {
        ObservableList<Image> icons = stage.getIcons();
        icons.setAll(fxIcons(path));
    }

    static void applyTaskbar(java.awt.Image image) {
        if (image == null) {
            return;
        }
        AwtSupport.run(() -> {
            try {
                if (!Taskbar.isTaskbarSupported()) {
                    return;
                }
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(image);
                }
            } catch (Exception ignored) {
                // 部分环境不支持改任务栏图标
            }
        });
    }

}
