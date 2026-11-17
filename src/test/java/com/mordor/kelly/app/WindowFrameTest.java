package com.mordor.kelly.app;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WindowFrameTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
        } catch (IllegalStateException alreadyStarted) {
            // Other JavaFX tests share the toolkit.
        }
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void topLeftResizeKeepsOppositeCornerAndHonorsMinimum() throws Exception {
        onFx(() -> {
            Stage stage = new Stage();
            WindowFrame.apply(stage);
            Scene scene = new Scene(new StackPane(), 700, 500);
            stage.setScene(scene);
            stage.setMinWidth(560);
            stage.setMinHeight(360);
            WindowFrame.makeResizable(stage, scene);
            stage.show();
            try {
                double right = stage.getX() + stage.getWidth();
                double bottom = stage.getY() + stage.getHeight();
                Event.fireEvent(scene, mouse(MouseEvent.MOUSE_PRESSED, 2, 2, 100, 100));
                Event.fireEvent(scene, mouse(MouseEvent.MOUSE_DRAGGED, 402, 402, 500, 500));
                assertEquals(560, stage.getWidth(), 0.1);
                assertEquals(360, stage.getHeight(), 0.1);
                assertEquals(right, stage.getX() + stage.getWidth(), 0.1);
                assertEquals(bottom, stage.getY() + stage.getHeight(), 0.1);
                Event.fireEvent(scene, mouse(MouseEvent.MOUSE_RELEASED, 402, 402, 500, 500));
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void windowControlsAreKeyboardAccessibleAndDoNotDragWindow() throws Exception {
        onFx(() -> {
            Stage stage = new Stage();
            WindowFrame.apply(stage);
            StackPane root = new StackPane();
            var buttons = WindowFrame.windowButtons(root);
            root.getChildren().add(buttons);
            WindowFrame.makeDraggable(root);
            stage.setScene(new Scene(root, 700, 500));
            stage.show();
            try {
                double x = stage.getX(), y = stage.getY();
                for (var node : buttons.getChildren()) {
                    Button button = assertInstanceOf(Button.class, node);
                    assertTrue(button.isFocusTraversable());
                    assertNotNull(button.getAccessibleText());
                    Event.fireEvent(button, mouse(MouseEvent.MOUSE_PRESSED, 20, 20, 100, 100));
                    Event.fireEvent(button, mouse(MouseEvent.MOUSE_DRAGGED, 60, 60, 140, 140));
                    button.disarm(); // Dragging must not activate a control in this test.
                    Event.fireEvent(button, mouse(MouseEvent.MOUSE_RELEASED, 60, 60, 140, 140));
                    assertEquals(x, stage.getX(), 0.1);
                    assertEquals(y, stage.getY(), 0.1);
                }
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void maximizeButtonTogglesRequestedWindowState() throws Exception {
        onFx(() -> {
            Stage stage = new Stage();
            WindowFrame.apply(stage);
            StackPane root = new StackPane();
            var buttons = WindowFrame.windowButtons(root);
            root.getChildren().add(buttons);
            stage.setScene(new Scene(root, 700, 500));
            // A stage not yet shown has no asynchronous window-manager state updates.
            Button maximize = (Button) buttons.getChildren().get(1);
            maximize.fire();
            assertTrue(stage.isMaximized());
            maximize.fire();
            assertFalse(stage.isMaximized());
        });
    }

    @Test
    void resizeCursorOverridesNodeCursorAndRestoresItInsideWindow() throws Exception {
        onFx(() -> {
            Stage stage = new Stage();
            WindowFrame.apply(stage);
            StackPane header = new StackPane();
            header.setStyle("-fx-cursor: move;");
            Scene scene = new Scene(header, 700, 500);
            stage.setScene(scene);
            WindowFrame.makeResizable(stage, scene);
            stage.show();
            try {
                header.applyCss();
                assertEquals(Cursor.MOVE, header.getCursor());
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_MOVED, 100, 2, 100, 2));
                assertEquals(Cursor.N_RESIZE, header.getCursor());
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_MOVED, 100, 30, 100, 30));
                assertEquals(Cursor.MOVE, header.getCursor());
                assertNull(scene.getCursor());
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void maximizedGeometryDoesNotReplaceNormalBounds() throws Exception {
        onFx(() -> {
            Stage login = new Stage();
            login.setScene(new Scene(new StackPane(), 720, 544));
            login.show();
            try {
                NormalWindowBounds tracker = new NormalWindowBounds(login);
                var normal = tracker.get();
                login.setMaximized(true);
                login.setWidth(1600);
                login.setHeight(1000);
                login.setX(0);
                login.setY(0);
                assertEquals(normal, tracker.get());
                Stage chat = new Stage();
                WindowFrame.apply(chat);
                var saved = tracker.get();
                chat.setScene(new Scene(new StackPane(), saved.width(), saved.height()));
                chat.setX(saved.x());
                chat.setY(saved.y());
                // These are the ordinary bounds before the new stage is maximized.
                chat.show();
                try {
                    assertEquals(normal.width(), chat.getScene().getWidth(), 0.1);
                    assertEquals(normal.height(), chat.getScene().getHeight(), 0.1);
                    assertEquals(normal.x(), chat.getX(), 0.1);
                    assertEquals(normal.y(), chat.getY(), 0.1);
                } finally {
                    chat.close();
                }
            } finally {
                login.close();
            }
        });
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type,
                                    double x, double y, double screenX, double screenY) {
        return new MouseEvent(type, x, y, screenX, screenY, MouseButton.PRIMARY, 1,
                false, false, false, false, type != MouseEvent.MOUSE_RELEASED,
                false, false, false, false, false, null);
    }
}
