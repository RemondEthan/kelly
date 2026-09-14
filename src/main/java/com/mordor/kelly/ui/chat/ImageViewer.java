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

final class ImageViewer {

    private ImageViewer() {}

    static void show(Path preview, Path original) {
        Stage stage = new Stage();
        stage.setTitle("图片");
        VBox root = new VBox(8);
        root.setPadding(new Insets(8));
        Label status = new Label();
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setFitWidth(720);
        Path file = original != null && Files.isRegularFile(original) ? original : preview;
        if (file != null && Files.isRegularFile(file)) {
            view.setImage(new Image(file.toUri().toString(), true));
            if (original != null && Files.isRegularFile(original)) {
                status.setText("原图");
            } else {
                status.setText("预览 · 原图传输中…");
            }
        } else {
            status.setText("原图不完整");
        }
        ScrollPane scroll = new ScrollPane(view);
        scroll.setFitToWidth(true);
        root.getChildren().addAll(status, scroll);
        stage.setScene(new Scene(root, 760, 560));
        stage.show();
    }
}
