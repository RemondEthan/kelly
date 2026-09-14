package com.mordor.kelly.service;

import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.MessageKind;
import com.mordor.kelly.model.Sender;

import java.time.LocalDateTime;

/**
 * Message ↔ 档案明文 JSON。不引入第三方 JSON 库。
 */
public final class HistoryCodec {

    private HistoryCodec() {}

    public static String encode(Message message) {
        String from = message.from() == null ? "" : message.from();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(Protocol.quote(message.id()))
                .append(",\"sender\":").append(Protocol.quote(message.sender().name()))
                .append(",\"from\":").append(Protocol.quote(from))
                .append(",\"timestamp\":").append(Protocol.quote(message.timestamp().toString()))
                .append(",\"content\":").append(Protocol.quote(message.content()));
        if (message.kind() == MessageKind.IMAGE) {
            sb.append(",\"kind\":\"IMAGE\"")
                    .append(",\"mediaId\":").append(Protocol.quote(nullToEmpty(message.mediaId())))
                    .append(",\"previewRel\":").append(Protocol.quote(nullToEmpty(message.previewRel())))
                    .append(",\"originalRel\":").append(Protocol.quote(nullToEmpty(message.originalRel())));
        }
        sb.append("}");
        return sb.toString();
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
        MessageKind kind = MessageKind.TEXT;
        String kindRaw = Protocol.stringField(json, "kind");
        if ("IMAGE".equals(kindRaw)) {
            kind = MessageKind.IMAGE;
        }
        try {
            if (kind == MessageKind.IMAGE) {
                return Message.image(
                        id,
                        Sender.valueOf(sender),
                        content,
                        LocalDateTime.parse(timestamp),
                        from == null ? "" : from,
                        nullToEmpty(Protocol.stringField(json, "mediaId")),
                        nullToEmpty(Protocol.stringField(json, "previewRel")),
                        nullToEmpty(Protocol.stringField(json, "originalRel")));
            }
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
