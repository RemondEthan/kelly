package com.mordor.kelly.app;

import javafx.collections.ObservableList;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 窗口标题栏、Windows 任务栏按钮、未读红点共用同一套图标。
 * 窗口图标要覆盖 Windows 任务栏 16/32 和 Debian 24。
 */
final class AppIcons {

    static final int[] STAGE_SIZES = {16, 24, 32, 48, 256};

    private AppIcons() {}

    static java.awt.Image awtImage(String path) {
        try (InputStream in = AppIcons.class.getResourceAsStream(path)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** Windows 托盘约 16px、Debian 约 24px；256 PNG 原图直接塞进去会糊。 */
    static java.awt.Image fitAwt(java.awt.Image src, int width, int height) {
        if (src == null || width <= 0 || height <= 0) {
            return src;
        }
        int sw = src.getWidth(null);
        int sh = src.getHeight(null);
        if (sw == width && sh == height) {
            return src;
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return out;
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
