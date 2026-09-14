package com.mordor.kelly.ui.chat.font;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.text.Text;

public final class ChatFontApplier {

    private ChatFontApplier() { }

    public static void apply(Node root, ChatFontSettings s) {
        if (root == null || s == null) {
            return;
        }
        if (s.fontFamily() != null) {
            String escaped = s.fontFamily().replace("'", "''");
            root.setStyle("-fx-font-family: '" + escaped + "'");
        }
        walk(root, s.scale().factor());
    }

    private static void walk(Node node, double factor) {
        try {
            if (node instanceof Labeled l && l.getFont() != null) {
                double base = l.getFont().getSize();
                if (base > 0) {
                    l.setStyle(formatFontSize(base * factor));
                }
            } else if (node instanceof TextInputControl t && t.getFont() != null) {
                double base = t.getFont().getSize();
                if (base > 0) {
                    t.setStyle(formatFontSize(base * factor));
                }
            } else if (node instanceof Text tx && tx.getFont() != null) {
                double base = tx.getFont().getSize();
                if (base > 0) {
                    tx.setStyle(formatFontSize(base * factor));
                }
            }
        } catch (Exception ignore) {
            // single node failure must not break sibling traversal
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                walk(child, factor);
            }
        }
    }

    private static String formatFontSize(double size) {
        long rounded = Math.round(size);
        return "-fx-font-size: " + rounded + "px;";
    }
}