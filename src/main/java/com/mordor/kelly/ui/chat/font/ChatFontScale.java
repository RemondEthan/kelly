package com.mordor.kelly.ui.chat.font;

public enum ChatFontScale {
    SMALL(0.9),
    MEDIUM(1.0),
    LARGE(1.1),
    XLARGE(1.2);

    private final double factor;

    ChatFontScale(double factor) {
        this.factor = factor;
    }

    public double factor() {
        return factor;
    }
}