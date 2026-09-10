package com.mordor.kelly.model;

import java.time.LocalDateTime;

public record Message(
        String id,
        Sender sender,
        String content,
        LocalDateTime timestamp,
        String from
) {
    public Message(String id, Sender sender, String content, LocalDateTime timestamp) {
        this(id, sender, content, timestamp, "");
    }
}
