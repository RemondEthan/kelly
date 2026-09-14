package com.mordor.kelly.ui.chat;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 图片查看器：独立窗口显示聊天中的图片。
 *
 * <p>支持预览图和原图两种状态：
 * <ul>
 *   <li>原图可用时直接显示原图</li>
 *   <li>原图尚未传完时显示预览图，并提示"传输中"</li>
 * </ul>
 *
 * <p>每次调用 {@link #show} 都会创建一个新的 {@link Stage}（独立窗口），
 * 图片加载使用 {@code Image(url, true)} 异步加载，避免阻塞 UI 线程。</p>
 *
 * <p>工具类，不能实例化。</p>
 */
final class ImageViewer {

    private ImageViewer() {}

    /**
     * 在新窗口中显示图片。
     *
     * @param preview 预览图文件路径（缩略图，始终存在）
     * @param original 原图文件路径，传输中可能为 null
     */
    static void show(Path preview, Path original) {
        Stage stage = new Stage();
        stage.setTitle("图片");
        VBox root = new VBox(8);
        root.setPadding(new Insets(8));

        Label status = new Label();       // 状态标签
        ImageView view = new ImageView();
        view.setPreserveRatio(true);       // 保持宽高比
        view.setFitWidth(720);             // 最大宽度 720px

        // 优先显示原图，没有则显示预览图
        Path file = original != null && Files.isRegularFile(original) ? original : preview;
        if (file != null && Files.isRegularFile(file)) {
            // new Image(uri, true) 第二个参数 true 表示异步加载，不阻塞 UI 线程
            view.setImage(new Image(file.toUri().toString(), true));
            if (original != null && Files.isRegularFile(original)) {
                status.setText("原图");
            } else {
                status.setText("预览 · 原图传输中…");
            }
        } else {
            status.setText("原图不完整");
        }

        // ScrollPane 包裹 ImageView，图片超出窗口时可滚动查看
        ScrollPane scroll = new ScrollPane(view);
        scroll.setFitToWidth(true);  // 内容宽度适配滚动面板宽度
        root.getChildren().addAll(status, scroll);

        stage.setScene(new Scene(root, 760, 560));
        stage.show();  // 非阻塞显示窗口
    }
}
