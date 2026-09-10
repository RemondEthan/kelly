package com.mordor.kelly.ui;

import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

/**
 * 圆形头像：有图按 cover 裁成整圆，否则显示占位文字或名字首字。
 */
public class AvatarView extends StackPane {

    public AvatarView(String name, Image photo, boolean self, double size) {
        this(name, photo, self, size, null);
    }

    public AvatarView(String name, Image photo, boolean self, double size, String emptyText) {
        getStyleClass().add("avatar-view");
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);

        double radius = size / 2;
        Circle clip = new Circle(radius);
        clip.centerXProperty().bind(widthProperty().divide(2));
        clip.centerYProperty().bind(heightProperty().divide(2));
        setClip(clip);

        if (photo != null && !photo.isError() && photo.getWidth() > 0 && photo.getHeight() > 0) {
            ImageView view = new ImageView(photo);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            double scale = Math.max(size / photo.getWidth(), size / photo.getHeight());
            view.setFitWidth(photo.getWidth() * scale);
            view.setFitHeight(photo.getHeight() * scale);
            getChildren().add(view);
            return;
        }

        Circle bg = new Circle(radius);
        bg.getStyleClass().add(self ? "avatar-fallback-self" : "avatar-fallback");
        Label initial = new Label(placeholder(name, emptyText));
        initial.getStyleClass().add(emptyText == null || emptyText.isBlank()
                ? "avatar-initial" : "avatar-placeholder");
        getChildren().addAll(bg, initial);
    }

    private static String placeholder(String name, String emptyText) {
        if (emptyText != null && !emptyText.isBlank()) {
            return emptyText;
        }
        if (name == null || name.isBlank()) {
            return "?";
        }
        return name.substring(0, 1);
    }
}
