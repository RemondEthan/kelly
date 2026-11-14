package com.mordor.kelly.ui.chat;

import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.Sender;
import com.mordor.kelly.ui.theme.AppTheme;
import com.mordor.kelly.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.skin.TextFieldSkin;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class EmojiInputTest {
    private final List<Stage> stages = new ArrayList<>();
    @BeforeAll static void start() throws Exception { FxTestSupport.startToolkit(); }
    @AfterEach void close() throws Exception { fx(() -> { stages.forEach(Stage::close); return null; }); }

    @Test void pickerRestoresCollapsedCaretAndTypingDoesNotOverwriteEmoji() throws Exception {
        EmojiTextField field = fx(() -> {
            var input = new EmojiTextField();input.setText("前后");
            Button anchor = new Button("emoji");
            var stage = show(new HBox(input, anchor));
            input.requestFocus();input.positionCaret(1);
            var picker = new EmojiPopover(input);picker.rememberSelection();anchor.requestFocus();picker.show(anchor);
            Popup popup = Window.getWindows().stream().filter(w -> w instanceof Popup).map(w -> (Popup) w).findFirst().orElseThrow();
            var grid = (Pane) popup.getContent().getFirst();
            Button choice = grid.getChildren().stream().filter(n -> n instanceof Button)
                    .map(n -> (Button) n).filter(b -> ((ImageView) b.getGraphic()).getImage().getUrl().contains("1f60a.png"))
                    .findFirst().orElseThrow();
            choice.requestFocus();choice.fire();return input;
        });
        FxTestSupport.drain();
        fx(() -> {
            assertEquals("前😊后", field.getText());
            assertEquals(3, field.getCaretPosition());assertEquals(3, field.getAnchor());
            field.replaceSelection("继续");assertEquals("前😊继续后", field.getText());
            return null;
        });
    }

    @Test void replacesSelectionAndPreservesUndoAndSubsequentTyping() throws Exception {
        fx(() -> {
            var field = new EmojiTextField();show(new HBox(field));
            field.setText("before old after");field.selectRange(7, 10);
            var picker = new EmojiPopover(field);picker.insertAtCaret("😊");
            assertEquals("before 😊 after", field.getText());
            assertEquals(9, field.getCaretPosition());assertEquals(0, field.getSelection().getLength());
            field.undo();assertEquals("before old after", field.getText());
            field.redo();assertEquals("before 😊 after", field.getText());
            field.replaceSelection("text");assertEquals("before 😊text after", field.getText());return null;
        });
        FxTestSupport.drain(); // Deferred focus repair must not move a caret after new text is entered.
    }

    @Test void bundledPicturesReplaceFontGlyphsAndFollowScrollAndFontSize() throws Exception {
        fx(() -> {
            var field = new EmojiTextField();field.setPrefWidth(130);field.setMaxWidth(130);
            var stage = show(new HBox(field));
            field.setText("abcdefghijklmnopqrstuvwxyz😊");field.positionCaret(field.getLength());
            stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
            assertEquals("abcdefghijklmnopqrstuvwxyz😊", field.getText());
            var pictures = (Pane) field.lookup(".emoji-input-pictures");
            assertEquals(1, pictures.getChildren().size());
            ImageView emoji = (ImageView) pictures.getChildren().getFirst();
            assertNotNull(emoji.getImage());assertTrue(emoji.getImage().getUrl().contains("/emoji/1f60a.png"));
            var nativeText = (Text) field.lookup(".text");
            assertEquals(field.getLength(), nativeText.getText().length());assertFalse(nativeText.getText().contains("😊"));
            var bounds = ((TextFieldSkin) field.getSkin()).getCharacterBounds(26);
            assertEquals(bounds.getMinX() + bounds.getWidth()/2, emoji.getLayoutX()+emoji.getFitWidth()/2, 1);
            assertTrue(emoji.getLayoutX() < field.getWidth());
            double small = emoji.getFitHeight();field.setFont(Font.font(24));
            stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
            assertTrue(emoji.getFitHeight() > small);
            field.selectAll();assertEquals(field.getText(), field.getSelectedText());return null;
        });
    }

    @Test void inputSnapshotContainsColoredEmojiPixels() throws Exception {
        EmojiTextField field = fx(() -> {
            var input = new EmojiTextField();input.setFont(Font.font(28));input.setText("A😊B");
            show(new HBox(input));return input;
        });
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(() -> ((ImageView) ((Pane) field.lookup(".emoji-input-pictures")).getChildren().getFirst()).getImage().getProgress() >= 1)) {
            assertTrue(System.nanoTime() < end, "Emoji image loading timed out");Thread.sleep(20);
        }
        fx(() -> {
            field.getScene().getRoot().applyCss();field.getScene().getRoot().layout();
            var image = field.snapshot(null, null);var pixels = image.getPixelReader();int colored = 0;
            for(int y=0;y<(int)image.getHeight();y++)for(int x=0;x<(int)image.getWidth();x++) {
                Color color = pixels.getColor(x,y);
                if(color.getRed()>.7 && color.getGreen()>.3 && color.getBlue()<.3 && color.getRed()>color.getGreen()) colored++;
            }
            assertTrue(colored>10, "Expected bundled yellow emoji pixels in the input itself");
            String output = System.getProperty("kelly.emoji.snapshot");
            if(output != null) {
                var png = new java.awt.image.BufferedImage((int)image.getWidth(), (int)image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,pixels.getArgb(x,y));
                javax.imageio.ImageIO.write(png, "png", new java.io.File(output));
            }
            return null;
        });
    }

    @Test void emojiDeletionAndCaretMovementNeverSplitUnicodeSequences() throws Exception {
        fx(() -> {
            var field = new EmojiTextField();field.setText("a😊b");field.positionCaret(2);
            assertEquals(3, field.getCaretPosition());field.replaceSelection("x");assertEquals("a😊xb", field.getText());
            field.positionCaret(3);assertTrue(field.deletePreviousChar());assertEquals("axb", field.getText());
            field.setText("a😊b");field.positionCaret(1);assertTrue(field.deleteNextChar());assertEquals("ab", field.getText());
            for(String emoji : EmojiImages.getSupportedEmojis()) {
                assertEquals(emoji.length(), EmojiTextField.displayText(emoji).length());
                assertEquals('\u2003', EmojiTextField.displayText(emoji).charAt(0));
            }
            return null;
        });
    }

    @Test void darkSystemMessagesAreWhiteAndLightThemeRemainsReadable() throws Exception {
        fx(() -> {
            var bubble = new MessageBubble(new Message("join", Sender.SYSTEM, "alice 已加入房间", LocalDateTime.now()),
                    "me", "alice", null, null, new SimpleDoubleProperty(320));
            VBox root = new VBox(bubble);root.getStylesheets().add(ChatPane.class.getResource("chat.css").toExternalForm());
            ThemeService.apply(root, AppTheme.DARK);show(root);root.applyCss();root.layout();
            var texts = bubble.lookupAll("Text").stream().filter(n -> n instanceof Text).map(n -> (Text) n).toList();
            assertFalse(texts.isEmpty());texts.forEach(t -> assertEquals(Color.WHITE, t.getFill()));
            ThemeService.apply(root, AppTheme.LIGHT);root.applyCss();
            texts.forEach(t -> assertNotEquals(Color.WHITE, t.getFill()));return null;
        });
    }

    private Stage show(javafx.scene.Parent root) {
        Stage stage = new Stage();stage.setScene(new Scene(root, 440, 130));stage.show();
        root.applyCss();root.layout();stages.add(stage);return stage;
    }
    private static <T> T fx(Callable<T> operation) throws Exception {
        var task = new FutureTask<>(operation);Platform.runLater(task);return task.get(15, TimeUnit.SECONDS);
    }
}
