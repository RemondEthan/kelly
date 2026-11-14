package com.mordor.kelly.ui.chat.font;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;

import static org.junit.jupiter.api.Assertions.*;

class ChatFontApplierTest {


    @BeforeAll
    static void startToolkit() throws Exception {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException already) {
        }
    }

    // JavaFX is process-scoped; do not exit it between test classes.

    @Test
    void colorAppliesToNewMessagesAndResetsInBothThemes() throws Exception {
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                for (var theme : com.mordor.kelly.ui.theme.AppTheme.values()) {
                    VBox chat = new VBox();
                    chat.getStyleClass().add("chat-root");
                    VBox root = new VBox(chat);
                    com.mordor.kelly.ui.theme.ThemeService.apply(root, theme);
                    new javafx.scene.Scene(root);
                    ChatFontApplier.apply(chat, new ChatFontSettings(null, ChatFontScale.MEDIUM, "#287ABC"));
                    Text message = new Text("new message");
                    javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow(message);
                    flow.getStyleClass().addAll("selectable-text-flow", "bubble-peer");
                    TextField input = new TextField("input");
                    chat.getChildren().addAll(flow, input);
                    root.applyCss();
                    assertEquals(javafx.scene.paint.Color.web("#287ABC"), message.getFill());
                    Text inputText = (Text) input.lookup(".text");
                    assertEquals(javafx.scene.paint.Color.web("#287ABC"), inputText.getFill());
                    ChatFontApplier.apply(chat, ChatFontSettings.defaults());
                    root.applyCss();
                    assertNotEquals(javafx.scene.paint.Color.web("#287ABC"), message.getFill());
                }
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        result.get(20, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void nullFamily_doesNotInjectFontFamilyStyle() {
        VBox root = new VBox(new Label("a"));
        ChatFontApplier.apply(root, new ChatFontSettings(null, ChatFontScale.MEDIUM));
        String style = root.getStyle();
        assertFalse(style.contains("-fx-font-family"),
                "null family must not inject -fx-font-family on root. style=" + style);
    }

    @Test
    void nonNullFamily_setsRootFontFamilyInStyle() {
        VBox root = new VBox(new Label("a"));
        ChatFontApplier.apply(root, new ChatFontSettings("Arial", ChatFontScale.MEDIUM));
        assertTrue(root.getStyle().contains("Arial"), "style=" + root.getStyle());
        assertTrue(root.getStyle().contains("-fx-font-family"), "style=" + root.getStyle());
    }

    @Test
    void largeScale_labelGetsLargerFontSizeStyle() {
        Label lbl = new Label("hi");
        VBox root = new VBox(lbl);
        double before = lbl.getFont().getSize();
        ChatFontApplier.apply(root, new ChatFontSettings(null, ChatFontScale.LARGE));
        assertTrue(lbl.getStyle().contains("-fx-font-size"),
                "Label should have -fx-font-size after apply, style=" + lbl.getStyle());
        long expected = Math.round(before * ChatFontScale.LARGE.factor());
        assertTrue(lbl.getStyle().contains(expected + "px"),
                "style=" + lbl.getStyle() + " expected to contain " + expected + "px");
    }

    @Test
    void mediumScale_textInputControlGetsFontSizeStyle() {
        TextField tf = new TextField();
        VBox root = new VBox(tf);
        double before = tf.getFont().getSize();
        ChatFontApplier.apply(root, new ChatFontSettings(null, ChatFontScale.MEDIUM));
        assertTrue(tf.getStyle().contains("-fx-font-size"),
                "TextField should have -fx-font-size after apply, style=" + tf.getStyle());
        long expected = Math.round(before * ChatFontScale.MEDIUM.factor());
        assertTrue(tf.getStyle().contains(expected + "px"),
                "style=" + tf.getStyle() + " expected to contain " + expected + "px");
    }

    @Test
    void walk_visitsNestedTextNode() {
        Text t = new Text("hello");
        VBox root = new VBox(t);
        ChatFontApplier.apply(root, new ChatFontSettings(null, ChatFontScale.SMALL));
        assertTrue(t.getStyle().contains("-fx-font-size"),
                "Text should have -fx-font-size after apply, style=" + t.getStyle());
    }

    @Test
    void deepNestedNodes_areVisited() {
        VBox inner = new VBox(new Label("inner"));
        VBox outer = new VBox(inner);
        ChatFontApplier.apply(outer, new ChatFontSettings(null, ChatFontScale.XLARGE));
        Label innerLabel = (Label) inner.getChildren().get(0);
        assertTrue(innerLabel.getStyle().contains("-fx-font-size"),
                "deep Label should be reached by walk, style=" + innerLabel.getStyle());
    }
}