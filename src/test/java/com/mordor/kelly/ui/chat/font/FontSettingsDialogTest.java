package com.mordor.kelly.ui.chat.font;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.control.ColorPicker;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FontSettingsDialogTest {
    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit is shared with the other JavaFX tests.
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void pickerKeyboardInteractionKeepsSettingsOpen() throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage dialog = null;
            var prefs = java.util.prefs.Preferences.userRoot().node("font-dialog-test-" + java.util.UUID.randomUUID());
            try {
                // Use isolated preferences so accidental application can be detected.
                dialog = FontSettingsDialog.createDialog(null,
                        new ChatFontSettings(null, ChatFontScale.MEDIUM, "#287ABC"),
                        new VBox(), new ChatFontSettingsService(prefs));
                dialog.show();
                ColorPicker picker = (ColorPicker) dialog.getScene().lookup(".color-picker");
                picker.requestFocus();
                Event.fireEvent(picker, key(KeyCode.ENTER));
                assertTrue(dialog.isShowing(), "Enter in the picker must not apply/close settings");
                assertNull(prefs.get("chat.font.color", null), "Choosing a color must not save settings");
                picker.show();
                assertTrue(picker.isShowing());
                picker.hide();
                // ComboBoxBase forwards Escape to its parent even with the palette closed.
                Event.fireEvent(picker, key(KeyCode.ESCAPE));
                assertTrue(dialog.isShowing(), "Cancelling color editing must keep settings open");
                Event.fireEvent(picker.getParent(), key(KeyCode.ESCAPE));
                assertTrue(dialog.isShowing(), "Forwarded Escape must not close settings");
                var cancel = dialog.getScene().getRoot().lookupAll(".button").stream()
                        .filter(node -> node instanceof javafx.scene.control.Button button
                                && button.getText().equals("取消"))
                        .map(node -> (javafx.scene.control.Button) node).findFirst().orElseThrow();
                cancel.fire();
                assertFalse(dialog.isShowing(), "Cancel explicitly closes settings");
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                if (dialog != null) dialog.close();
                try {
                    prefs.removeNode();
                } catch (java.util.prefs.BackingStoreException failure) {
                    result.completeExceptionally(failure);
                }
            }
        });
        result.get(20, TimeUnit.SECONDS);
    }

    @Test
    void repeatedEscapeCannotDismissFontOrColorPopupUntilRelease() throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage dialog = null;
            try {
                dialog = FontSettingsDialog.createDialog(null,
                        new ChatFontSettings(null, ChatFontScale.MEDIUM, "#287ABC"),
                        new VBox(), null);
                dialog.show();
                var font = (javafx.scene.control.ComboBox<?>) dialog.getScene().lookup(".combo-box");
                var color = (ColorPicker) dialog.getScene().lookup(".color-picker");
                for (javafx.scene.control.ComboBoxBase<?> control : java.util.List.of(font, color)) {
                    control.requestFocus();
                    control.show();
                    assertTrue(control.isShowing());
                    for (int i = 0; i < 60; i++) {
                        Event.fireEvent(control, key(KeyCode.ESCAPE));
                        assertTrue(control.isShowing(), control.getClass().getSimpleName()
                                + " must stay open during repeated Escape presses");
                    }
                    Event.fireEvent(control, new KeyEvent(KeyEvent.KEY_RELEASED, "", "",
                            KeyCode.ESCAPE, false, false, false, false));
                    assertFalse(control.isShowing(), "Releasing Escape cancels the popup");
                    assertTrue(dialog.isShowing(), "Cancelling a popup must keep settings open");
                    // Reopening while repeats continue must work as well.
                    control.show();
                    Event.fireEvent(control, key(KeyCode.ESCAPE));
                    assertTrue(control.isShowing());
                    control.hide();
                }
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                if (dialog != null) dialog.close();
            }
        });
        result.get(20, TimeUnit.SECONDS);
    }

    @Test
    void uncheckingFollowThemeWithMouseKeepsDialogOpen() throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage owner = new Stage();
            owner.setScene(new javafx.scene.Scene(new VBox(), 600, 400));
            owner.show();
            Stage dialog = FontSettingsDialog.createDialog(owner, ChatFontSettings.defaults(),
                    new VBox(), null);
            dialog.show();
            javafx.animation.PauseTransition ready = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(300));
            ready.setOnFinished(event -> {
                try {
                    var follow = (javafx.scene.control.CheckBox) dialog.getScene().lookup(".check-box");
                    var bounds = follow.localToScreen(follow.getBoundsInLocal());
                    var robot = new javafx.scene.robot.Robot();
                    robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                    robot.mouseClick(javafx.scene.input.MouseButton.PRIMARY);
                    javafx.animation.PauseTransition clicked = new javafx.animation.PauseTransition(
                            javafx.util.Duration.millis(300));
                    clicked.setOnFinished(done -> {
                        try {
                            assertFalse(follow.isSelected(), "Mouse must reach the checkbox");
                            assertTrue(dialog.isShowing(), "Unchecking follow theme must keep settings open");
                            var picker = (ColorPicker) dialog.getScene().lookup(".color-picker");
                            assertFalse(picker.isDisabled());
                            result.complete(null);
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        } finally {
                            dialog.close();
                            owner.close();
                        }
                    });
                    clicked.play();
                } catch (Throwable failure) {
                    dialog.close();
                    owner.close();
                    result.completeExceptionally(failure);
                }
            });
            ready.play();
        });
        result.get(20, TimeUnit.SECONDS);
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }
}
