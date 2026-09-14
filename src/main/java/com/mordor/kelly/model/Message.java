package com.mordor.kelly.model;

import java.time.LocalDateTime;

public record Message(
        String id,
        Sender sender,
        String content,
        LocalDateTime timestamp,
        String from,
        MessageKind kind,
        String mediaId,
        String previewRel,
        String originalRel
) {
    public Message(String id, Sender sender, String content, LocalDateTime timestamp) {
        this(id, sender, content, timestamp, "");
    }

    public Message(String id, Sender sender, String content, LocalDateTime timestamp, String from) {
        this(id, sender, content, timestamp, from, MessageKind.TEXT, "", "", "");
    }

    public static Message image(
            String id,
            Sender sender,
            String caption,
            LocalDateTime timestamp,
            String from,
            String mediaId,
            String previewRel,
            String originalRel) {
        return new Message(
                id,
                sender,
                caption == null ? "" : caption,
                timestamp,
                from == null ? "" : from,
                MessageKind.IMAGE,
                mediaId == null ? "" : mediaId,
                previewRel == null ? "" : previewRel,
                originalRel == null ? "" : originalRel);
    }

    public boolean image() {
        return kind == MessageKind.IMAGE;
    }
}
