package com.mordor.kelly.ui.chat;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ImageViewerTest {
    @TempDir Path dir;
    final List<Stage> windows = new ArrayList<>();

    @BeforeAll static void startToolkit() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException alreadyStarted) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> { Platform.setImplicitExit(false); return null; });
    }

    @AfterEach void closeWindows() throws Exception {
        fx(() -> { windows.forEach(Stage::close); return null; });
    }

    @Test void repeatedOpenReusesWindowAndCloseAllowsFreshWindow() throws Exception {
        Path preview = image("preview.png", 160, 80);
        Path original = dir.resolve("original.png");
        Stage first = open(preview, original);
        Stage second = open(preview, dir.resolve("./original.png"));
        assertSame(first, second);
        fx(() -> { first.close(); return null; });
        assertNotSame(first, open(preview, original));
    }

    @Test void doubleClickOpensOneWindowAndSecondaryClickDoesNotOpenOne() throws Exception {
        Path preview = image("preview.png", 160, 80);
        fx(() -> {
            var message = com.mordor.kelly.model.Message.image("id", com.mordor.kelly.model.Sender.PEER,
                    "", java.time.LocalDateTime.now(), "Peer", "id", "preview.png", "original.png");
            var bubble = new MessageBubble(message, "Me", "Peer", null, null,
                    new javafx.beans.property.SimpleDoubleProperty(400), preview, dir.resolve("original.png"));
            var thumbnail = findImage(bubble);
            fireClick(thumbnail, javafx.scene.input.MouseButton.SECONDARY, 1);
            assertEquals(0, imageWindows().size());
            fireClick(thumbnail, javafx.scene.input.MouseButton.PRIMARY, 1);
            fireClick(thumbnail, javafx.scene.input.MouseButton.PRIMARY, 2);
            windows.addAll(imageWindows());
            assertEquals(1, windows.size());
            return null;
        });
    }

    private static List<Stage> imageWindows() {
        return javafx.stage.Window.getWindows().stream().filter(w -> w instanceof Stage)
                .map(w -> (Stage) w).filter(w -> "图片".equals(w.getTitle())).toList();
    }

    private static ImageView findImage(javafx.scene.Parent parent) {
        for (var child : parent.getChildrenUnmodifiable()) {
            if (child instanceof ImageView image) return image;
            if (child instanceof javafx.scene.Parent nested) {
                ImageView found = findImage(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void fireClick(ImageView view, javafx.scene.input.MouseButton button, int count) {
        javafx.event.Event.fireEvent(view, new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, button, count,
                false, false, false, false, false, false, false, false, false, true, null));
    }

    @Test void fitsResizedViewportAndSupportsZoomAndActualSize() throws Exception {
        Path original = image("original.png", 1600, 800);
        Stage stage = open(original, original);
        await(() -> view(stage).getImage() != null);
        fx(() -> { stage.setWidth(650); stage.setHeight(450); return null; });
        await(() -> view(stage).getFitWidth() < 650);
        double small = fx(() -> view(stage).getFitWidth());
        fx(() -> { stage.setWidth(1000); stage.setHeight(750); return null; });
        await(() -> view(stage).getFitWidth() > small + 100);
        double fitted = fx(() -> view(stage).getFitWidth());
        fx(() -> {
            var scroll = (ScrollPane) ((BorderPane) stage.getScene().getRoot()).getCenter();
            javafx.event.Event.fireEvent(scroll, new javafx.scene.input.ScrollEvent(
                    javafx.scene.input.ScrollEvent.SCROLL, 1, 1, 1, 1,
                    false, true, false, false, false, false, 0, 40, 0, 40,
                    javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            return null;
        });
        assertEquals(fitted * 1.25, fx(() -> view(stage).getFitWidth()), 0.01);
        click(stage, "image-fit");
        click(stage, "image-zoom-in");
        assertTrue(fx(() -> view(stage).getFitWidth()) > fitted);
        click(stage, "image-actual-size");
        assertEquals(1600, fx(() -> view(stage).getFitWidth()), 0.01);
        click(stage, "image-zoom-out");
        assertEquals(1280, fx(() -> view(stage).getFitWidth()), 0.01);
        click(stage, "image-fit");
        await(() -> view(stage).getFitWidth() < 1000);
    }

    @Test void deliveredOriginalReplacesPreviewInExistingWindow() throws Exception {
        Path preview = image("preview.png", 160, 80);
        Path original = dir.resolve("original.png");
        Stage stage = open(preview, original);
        await(() -> view(stage).getImage() != null && view(stage).getImage().getWidth() == 160);
        image("original.png", 1600, 800);
        // Delivery can originate outside the JavaFX thread.
        ImageViewer.originalReady(original);
        await(() -> view(stage).getImage() != null && view(stage).getImage().getWidth() == 1600);
        assertSame(stage, open(preview, original));
        fx(() -> { stage.close(); return null; });
        ImageViewer.originalReady(original);
        assertFalse(fx(stage::isShowing));
    }

    private Stage open(Path preview, Path original) throws Exception {
        Stage stage = fx(() -> ImageViewer.show(preview, original));
        windows.add(stage);
        return stage;
    }

    private Path image(String name, int width, int height) throws Exception {
        Path file = dir.resolve(name);
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    private static ImageView view(Stage stage) {
        var root = (BorderPane) stage.getScene().getRoot();
        var scroll = (ScrollPane) root.getCenter();
        return (ImageView) ((StackPane) scroll.getContent()).getChildren().getFirst();
    }

    private static void click(Stage stage, String id) throws Exception {
        fx(() -> { ((Button) stage.getScene().lookup("#" + id)).fire(); return null; });
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (fx(condition)) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for image/layout update");
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
