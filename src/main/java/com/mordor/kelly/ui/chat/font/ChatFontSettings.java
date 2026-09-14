package com.mordor.kelly.ui.chat.font;

public record ChatFontSettings(String fontFamily, ChatFontScale scale) {

    public static ChatFontSettings defaults() {
        return new ChatFontSettings(null, ChatFontScale.MEDIUM);
    }
}