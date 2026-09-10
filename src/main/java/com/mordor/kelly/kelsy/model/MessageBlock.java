package com.mordor.kelly.kelsy.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class MessageBlock {

    public enum Kind { TEXT, TOOL, THINKING }

    private final Kind kind;
    private final StringProperty content = new SimpleStringProperty("");
    private final BooleanProperty streaming = new SimpleBooleanProperty(false);
    private final String toolName;
    private final StringProperty argsPreview = new SimpleStringProperty("");
    private final StringProperty openPath = new SimpleStringProperty("");

    private MessageBlock(Kind kind, String toolName, String argsPreview) {
        this.kind = kind;
        this.toolName = toolName;
        this.argsPreview.set(argsPreview == null ? "" : argsPreview);
    }

    public static MessageBlock text() {
        MessageBlock b = new MessageBlock(Kind.TEXT, null, "");
        b.streaming.set(true);
        return b;
    }

    public static MessageBlock text(String content) {
        MessageBlock b = new MessageBlock(Kind.TEXT, null, "");
        b.content.set(content == null ? "" : content);
        return b;
    }

    public static MessageBlock tool(String name, String argsPreview) {
        return new MessageBlock(Kind.TOOL, name, argsPreview);
    }

    public static MessageBlock thinking() {
        MessageBlock b = new MessageBlock(Kind.THINKING, null, "");
        b.streaming.set(true);
        return b;
    }

    public Kind kind() {
        return kind;
    }

    public String toolName() {
        return toolName;
    }

    public String argsPreview() {
        return argsPreview.get();
    }

    public void appendArgs(String delta) {
        if (delta != null && !delta.isEmpty()) {
            argsPreview.set(argsPreview.get() + delta);
        }
    }

    public String content() {
        return content.get();
    }

    public StringProperty contentProperty() {
        return content;
    }

    public StringProperty openPathProperty() {
        return openPath;
    }

    public BooleanProperty streamingProperty() {
        return streaming;
    }

    public void append(String delta) {
        if (delta != null && !delta.isEmpty()) {
            content.set(content.get() + delta);
        }
    }

    public void finish() {
        streaming.set(false);
    }
}
