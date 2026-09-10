package com.mordor.kelly.ui.chat;

import javafx.geometry.Pos;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageBubbleTest {

    @Test
    void formatsFullDateTime() {
        LocalDateTime at = LocalDateTime.of(2026, 8, 28, 15, 16, 32);
        assertEquals("2026-08-28 15:16:32", MessageBubble.formatTime(at));
    }

    @Test
    void selfMetaAlignsToAvatarSide() {
        assertEquals(Pos.CENTER_RIGHT, MessageBubble.sideMetaAlignment(true));
        assertEquals(Pos.CENTER_LEFT, MessageBubble.sideMetaAlignment(false));
    }
}
