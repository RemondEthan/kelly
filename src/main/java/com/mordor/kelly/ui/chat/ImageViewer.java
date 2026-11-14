package com.mordor.kelly.ui.chat;

import com.mordor.kelly.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** One window per image, with viewport fitting, zoom and live original-image delivery. FX thread only. */
final class ImageViewer {
    private static final Map<Path, Viewer> OPEN = java.util.Collections.synchronizedMap(new HashMap<>());

    private ImageViewer() {}

    static Stage show(Path preview, Path original) {
        return show(preview, original, null);
    }

    static Stage show(Path preview, Path original, Window owner) {
        Path key = key(original != null ? original : preview);
        Viewer viewer = OPEN.get(key);
        if (viewer == null) {
            viewer = new Viewer(key, preview, original, owner);
            OPEN.put(key, viewer);
        }
        viewer.refresh();
        viewer.stage.setIconified(false);
        viewer.stage.show();
        viewer.stage.toFront();
        viewer.stage.requestFocus();
        return viewer.stage;
    }

    /** Called only after the completed original has been committed to its final path. */
    static void originalReady(Path original) {
        if (!OPEN.containsKey(key(original))) return;
        Runnable refresh = () -> {
            Viewer viewer = OPEN.get(key(original));
            if (viewer != null) viewer.refresh();
        };
        if (Platform.isFxApplicationThread()) refresh.run(); else Platform.runLater(refresh);
    }

    private static Path key(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static final class Viewer {
        final Stage stage = new Stage();
        final ImageView view = new ImageView();
        final Label status = new Label();
        final Label percentage = new Label();
        final StackPane canvas = new StackPane(view);
        final ScrollPane scroll = new ScrollPane(canvas);
        final Path preview;
        final Path original;
        Path loadingPath;
        Image loadingImage;
        boolean fitting = true;
        double scale = 1;

        Viewer(Path key, Path preview, Path original, Window owner) {
            this.preview = preview;
            this.original = original;
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("图片");
            view.setPreserveRatio(true);
            view.setSmooth(true);
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(true);
            scroll.setPannable(true);
            scroll.viewportBoundsProperty().addListener((obs, old, bounds) -> resize());
            scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
                if (event.isControlDown() && event.getDeltaY() != 0) {
                    zoom(scale * (event.getDeltaY() > 0 ? 1.25 : 0.8));
                    event.consume();
                }
            });

            Button smaller = new Button("−");
            smaller.setId("image-zoom-out");
            smaller.setOnAction(event -> zoom(scale / 1.25));
            Button larger = new Button("＋");
            larger.setId("image-zoom-in");
            larger.setOnAction(event -> zoom(scale * 1.25));
            Button actual = new Button("100%");
            actual.setId("image-actual-size");
            actual.setOnAction(event -> zoom(1));
            Button fit = new Button("适应窗口");
            fit.setId("image-fit");
            fit.setOnAction(event -> { fitting = true; resize(); });
            HBox toolbar = new HBox(8, status, smaller, larger, actual, fit, percentage);
            toolbar.setPadding(new Insets(8));
            BorderPane root = new BorderPane(scroll, toolbar, null, null, null);
            ThemeService.apply(root, new ThemeService().load());
            var bounds = (owner == null ? Screen.getPrimary()
                    : Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight())
                            .stream().findFirst().orElse(Screen.getPrimary())).getVisualBounds();
            Scene scene = new Scene(root, Math.min(1100, bounds.getWidth() * 0.9),
                    Math.min(800, bounds.getHeight() * 0.9));
            scene.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ESCAPE) stage.close();
            });
            stage.setScene(scene);
            stage.setOnHidden(event -> {
                OPEN.remove(key, this);
                Image pending = loadingImage;
                loadingImage = null;
                if (pending != null) pending.cancel();
                view.setImage(null);
            });
        }

        void refresh() {
            boolean hasOriginal = original != null && Files.isRegularFile(original);
            Path file = hasOriginal ? original : preview;
            if (file == null || !Files.isRegularFile(file)) {
                status.setText("原图不完整");
                return;
            }
            if (key(file).equals(loadingPath)) return;
            loadingPath = key(file);
            Image pending = loadingImage;
            loadingImage = null;
            if (pending != null) pending.cancel();
            Image image = new Image(file.toUri().toString(), true);
            loadingImage = image;
            status.setText(hasOriginal ? "正在加载原图…" : "预览 · 原图传输中…");
            Runnable loaded = () -> {
                if (loadingImage != image) return;
                if (image.isError()) {
                    status.setText(hasOriginal ? "原图加载失败" : "预览加载失败");
                    loadingPath = null; // A later open/delivery may retry.
                } else if (image.getProgress() >= 1) {
                    view.setImage(image);
                    status.setText(hasOriginal ? "原图" : "预览 · 原图传输中…");
                    resize();
                }
            };
            image.progressProperty().addListener((obs, old, progress) -> loaded.run());
            image.errorProperty().addListener((obs, old, error) -> loaded.run());
            loaded.run();
        }

        void zoom(double value) {
            fitting = false;
            scale = Math.max(0.05, Math.min(16, value));
            resize();
        }

        void resize() {
            Image image = view.getImage();
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return;
            var viewport = scroll.getViewportBounds();
            if (fitting) {
                if (viewport.getWidth() <= 2 || viewport.getHeight() <= 2) return;
                scale = Math.min((viewport.getWidth() - 2) / image.getWidth(),
                        (viewport.getHeight() - 2) / image.getHeight());
            }
            double width = image.getWidth() * scale;
            double height = image.getHeight() * scale;
            view.setFitWidth(width);
            view.setFitHeight(height);
            canvas.setMinSize(width, height);
            percentage.setText(Math.round(scale * 100) + "%");
        }
    }
}
