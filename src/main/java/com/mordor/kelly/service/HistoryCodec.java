package com.mordor.kelly.service;

import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.MessageKind;
import com.mordor.kelly.model.Sender;

import java.time.LocalDateTime;

/**
 * 历史消息编解码器
 *
 * 本类负责 Message 对象与 JSON 字符串之间的转换
 * 用于聊天历史的序列化和反序列化
 *
 * JSON 格式示例：
 * - 文本消息：{"id":"xxx","sender":"ME","from":"xxx","timestamp":"xxx","content":"xxx"}
 * - 图片消息：{"id":"xxx","sender":"PEER","from":"xxx","timestamp":"xxx","content":"xxx","kind":"IMAGE","mediaId":"xxx","previewRel":"xxx","originalRel":"xxx"}
 *
 * 不引入第三方 JSON 库，使用手写解析
 */
public final class HistoryCodec {

    /**
     * 私有构造方法，防止实例化
     */
    private HistoryCodec() {}

    /**
     * 编码消息为 JSON 字符串
     * 手动拼接 JSON，避免引入第三方库
     *
     * @param message 要编码的消息
     * @return JSON 字符串
     */
    public static String encode(Message message) {
        String from = message.from() == null ? "" : message.from();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(Protocol.quote(message.id()))
                .append(",\"sender\":").append(Protocol.quote(message.sender().name()))
                .append(",\"from\":").append(Protocol.quote(from))
                .append(",\"timestamp\":").append(Protocol.quote(message.timestamp().toString()))
                .append(",\"content\":").append(Protocol.quote(message.content()));
        // 如果是图片消息，添加额外字段
        if (message.kind() == MessageKind.IMAGE) {
            sb.append(",\"kind\":\"IMAGE\"")
                    .append(",\"mediaId\":").append(Protocol.quote(nullToEmpty(message.mediaId())))
                    .append(",\"previewRel\":").append(Protocol.quote(nullToEmpty(message.previewRel())))
                    .append(",\"originalRel\":").append(Protocol.quote(nullToEmpty(message.originalRel())));
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 解码 JSON 字符串为消息对象
     * 使用 Protocol.stringField 提取字段值
     *
     * @param json JSON 字符串
     * @return 解析后的消息对象
     * @throws IllegalArgumentException JSON 不完整或格式错误时抛出
     */
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

    /**
     * 空值转空字符串
     * @param s 输入字符串
     * @return 非空字符串
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
