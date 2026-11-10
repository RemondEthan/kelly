package com.mordor.kelly.ui.chat;

import javafx.scene.control.Skin;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextFieldSkin;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;

import java.util.*;

/** Native editing/IME/clipboard, with bundled PNG emoji instead of platform font glyphs. */
final class EmojiTextField extends TextField {
    private static final Map<Integer,List<String>> EMOJIS = emojiRuns();

    @Override protected Skin<?> createDefaultSkin() { return new EmojiSkin(this); }

    @Override public void selectRange(int anchor, int caret) {
        super.selectRange(boundary(anchor, anchor >= getAnchor()), boundary(caret, caret >= getCaretPosition()));
    }

    private int boundary(int offset, boolean forward) {
        String text = Objects.toString(getText(), "");
        offset = Math.max(0, Math.min(offset, text.length()));
        for (int i = 0; i < text.length();) {
            String emoji = match(text, i);
            int end = i + (emoji == null ? 1 : emoji.length());
            if (emoji != null && offset > i && offset < end) return forward ? end : i;
            i = end;
        }
        return offset;
    }

    @Override public boolean deletePreviousChar() {
        if (getSelection().getLength() == 0) {
            String text = Objects.toString(getText(), "");
            for (int i = 0; i < getCaretPosition();) {
                String emoji = match(text, i);
                int end = i + (emoji == null ? 1 : emoji.length());
                if (emoji != null && end == getCaretPosition()) { replaceText(i, end, ""); return true; }
                i = end;
            }
        }
        return super.deletePreviousChar();
    }

    @Override public boolean deleteNextChar() {
        String text = Objects.toString(getText(), "");
        int caret = getCaretPosition();
        if (getSelection().getLength() == 0 && caret < text.length()) {
            String emoji = match(text, caret);
            if (emoji != null) { replaceText(caret, caret + emoji.length(), ""); return true; }
        }
        return super.deleteNextChar();
    }

    private static Map<Integer,List<String>> emojiRuns() {
        Map<Integer,List<String>> groups = new HashMap<>();
        for (String emoji : EmojiImages.getSupportedEmojis()) {
            groups.computeIfAbsent(emoji.codePointAt(0), ignored -> new ArrayList<>()).add(emoji);
        }
        groups.values().forEach(list -> list.sort(Comparator.comparingInt(String::length).reversed()));
        return groups;
    }

    private static String match(String text, int offset) {
        for (String emoji : EMOJIS.getOrDefault(text.codePointAt(offset), List.of())) {
            if (text.startsWith(emoji, offset)) return emoji;
        }
        return null;
    }

    /** Keep UTF-16 offsets unchanged so native selection, caret and IME indices still map exactly. */
    static String displayText(String text) {
        text = Objects.toString(text, "");
        StringBuilder display = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            String emoji = match(text, i);
            if (emoji == null) display.append(text.charAt(i++));
            else {
                display.append('\u2003'); // One em of layout space for the PNG.
                display.append("\u200b".repeat(emoji.length() - 1));
                i += emoji.length();
            }
        }
        return display.toString();
    }

    private static final class EmojiSkin extends TextFieldSkin {
        private record Glyph(int offset, ImageView image) {}
        private final Pane pictures = new Pane();
        private final Rectangle clip = new Rectangle();
        private final List<Glyph> glyphs = new ArrayList<>();

        EmojiSkin(EmojiTextField field) {
            super(field);
            pictures.setManaged(false);
            pictures.setMouseTransparent(true);
            pictures.getStyleClass().add("emoji-input-pictures");
            pictures.setClip(clip);
            getChildren().add(pictures);
            registerChangeListener(field.textProperty(), ignored -> rebuild());
            registerInvalidationListener(field.caretPositionProperty(), ignored -> field.requestLayout());
            registerInvalidationListener(field.selectionProperty(), ignored -> field.requestLayout());
            registerInvalidationListener(field.fontProperty(), ignored -> field.requestLayout());
            registerInvalidationListener(field.alignmentProperty(), ignored -> field.requestLayout());
            rebuild();
        }

        @Override protected String maskText(String text) { return displayText(text); }

        private void rebuild() {
            glyphs.clear();pictures.getChildren().clear();
            String text = Objects.toString(getSkinnable().getText(), "");
            for (int i = 0; i < text.length();) {
                String emoji = match(text, i);
                if (emoji == null) i++;
                else {
                    ImageView image = EmojiImages.view(emoji, getSkinnable().getFont().getSize());
                    glyphs.add(new Glyph(i, image));pictures.getChildren().add(image);
                    i += emoji.length();
                }
            }
            getSkinnable().requestLayout();
        }

        @Override protected void layoutChildren(double x, double y, double width, double height) {
            super.layoutChildren(x, y, width, height);
            pictures.resizeRelocate(0, 0, getSkinnable().getWidth(), getSkinnable().getHeight());
            clip.setX(x);clip.setY(y);clip.setWidth(width);clip.setHeight(height);
            for (Glyph glyph : glyphs) {
                var bounds = getCharacterBounds(glyph.offset());
                double size = Math.max(1, Math.min(bounds.getWidth(), bounds.getHeight()) - 1);
                glyph.image().setFitWidth(size);glyph.image().setFitHeight(size);
                glyph.image().relocate(bounds.getMinX() + (bounds.getWidth() - size) / 2,
                        bounds.getMinY() + (bounds.getHeight() - size) / 2);
            }
        }

        @Override public void dispose() {
            pictures.getChildren().clear();glyphs.clear();getChildren().remove(pictures);
            super.dispose();
        }
    }
}
