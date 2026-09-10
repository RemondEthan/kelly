package com.mordor.kelly.service;

import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.Sender;

import java.time.LocalDateTime;

/**
 * Message ↔ 档案明文 JSON。不引入第三方 JSON 库。
 */
public final class HistoryCodec {

    private HistoryCodec() {}

    public static String encode(Message message) {
        String from = message.from() == null ? "" : message.from();
        return "{\"id\":" + Protocol.quote(message.id())
                + ",\"sender\":" + Protocol.quote(message.sender().name())
                + ",\"from\":" + Protocol.quote(from)
                + ",\"timestamp\":" + Protocol.quote(message.timestamp().toString())
                + ",\"content\":" + Protocol.quote(message.content())
                + "}";
    }

    public static Message decode(String json) {
        String id = Protocol.stringField(json, "id");
        String sender = Protocol.stringField(json, "sender");
        String from = Protocol.stringField(json, "from");
        String timestamp = Protocol.stringField(json, "timestamp");
        String content = Protocol.stringField(json, "content");
        if (id == null || sender == null || timestamp == null || content == null) {
            throw new IllegalArgumentException("incomplete history record");
        }
        try {
            return new Message(
                    id,
                    Sender.valueOf(sender),
                    content,
                    LocalDateTime.parse(timestamp),
                    from == null ? "" : from);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid history record", e);
        }
    }
}
