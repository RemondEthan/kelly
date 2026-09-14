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
 * 应用图标管理器——统一管理窗口标题栏、任务栏按钮、未读红点的图标。
 *
 * <p>多尺寸图标策略：
 * <ul>
 *   <li>{@link #STAGE_SIZES} 定义了 5 种尺寸：16、24、32、48、256</li>
 *   <li>JavaFX 窗口图标需要多尺寸，因为 Windows 任务栏用 16/32px，Linux 用 24px</li>
 *   <li>macOS Retina 显示器需要大尺寸图标以保证清晰度</li>
 * </ul>
 *
 * <p>两套图像 API：
 * <ul>
 *   <li>{@code fx*} 方法：使用 JavaFX {@link Image} API，用于窗口标题栏图标</li>
 *   <li>{@code awt*} 方法：使用 AWT {@link java.awt.Image} API，用于系统托盘和任务栏</li>
 * </ul>
 *
 * <p>为什么不能用同一套：JavaFX 和 AWT 是两套独立的图形工具包，
 * 它们的 Image 类不兼容，需要分别加载和处理。
 */
final class AppIcons {

    /** 窗口图标需要的多尺寸列表：16/24/32/48/256 像素 */
    static final int[] STAGE_SIZES = {16, 24, 32, 48, 256};

    private AppIcons() {}

    /**
     * 从 classpath 加载 AWT 格式的图像。
     *
     * @param path 图标资源路径（如 "/icons/kelly.png"）
     * @return AWT Image 对象，加载失败返回 null
     */
    static java.awt.Image awtImage(String path) {
        try (InputStream in = AppIcons.class.getResourceAsStream(path)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 将 AWT 图像缩放至指定尺寸。
     *
     * <p>使用双线性插值（BILINEAR）和质量优先渲染，保证缩放后清晰。
     * 如果源图像尺寸已匹配目标尺寸，直接返回原图不复制。
     *
     * @param src    原始 AWT 图像
     * @param width  目标宽度（像素）
     * @param height 目标高度（像素）
     * @return 缩放后的新图像，参数无效时返回原图
     */
    static java.awt.Image fitAwt(java.awt.Image src, int width, int height) {
        if (src == null || width <= 0 || height <= 0) {
            return src;
        }
        int sw = src.getWidth(null);
        int sh = src.getHeight(null);
        if (sw == width && sh == height) {
            return src; // 尺寸已匹配，无需缩放
        }
        // 创建目标尺寸的 BufferedImage，使用 ARGB 支持透明度
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        // 设置高质量缩放：双线性插值 + 渲染质量优先
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose(); // 释放 Graphics2D 资源
        return out;
    }

    /**
     * 从 classpath 加载多尺寸的 JavaFX 图像列表。
     *
     * <p>为 {@link #STAGE_SIZES} 中的每个尺寸创建一个 {@link Image}，
     * JavaFX 会根据窗口实际需要的尺寸自动选择最合适的。
     * 参数 {@code smooth=true} 启用平滑缩放，{@code backgroundLoading=true} 异步加载。
     *
     * @param path 图标资源路径
     * @return 多尺寸图像列表，资源不存在时返回空列表
     */
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

    /**
     * 将多尺寸图标列表应用到 Stage 的窗口标题栏。
     *
     * @param stage 目标窗口
     * @param path  图标资源路径
     */
    static void applyStage(Stage stage, String path) {
        ObservableList<Image> icons = stage.getIcons();
        icons.setAll(fxIcons(path));
    }

    /**
     * 设置 Windows/Linux 任务栏按钮图标。
     *
     * <p>使用 AWT Taskbar API（Java 9+），在 AWT 线程中执行。
     * 部分桌面环境不支持此功能，静默忽略异常。
     *
     * @param image 任务栏图标（AWT Image）
     */
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
