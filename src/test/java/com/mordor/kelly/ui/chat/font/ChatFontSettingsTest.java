package com.mordor.kelly.ui.chat.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatFontSettingsTest {

    @Test
    void scaleFactors_areFixedPerSpec() {
        assertEquals(0.9, ChatFontScale.SMALL.factor(), 1e-9);
        assertEquals(1.0, ChatFontScale.MEDIUM.factor(), 1e-9);
        assertEquals(1.1, ChatFontScale.LARGE.factor(), 1e-9);
        assertEquals(1.2, ChatFontScale.XLARGE.factor(), 1e-9);
    }

    @Test
    void defaults_areMediumAndNullFamily() {
        ChatFontSettings d = ChatFontSettings.defaults();
        assertNull(d.fontFamily());
        assertEquals(ChatFontScale.MEDIUM, d.scale());
    }
}