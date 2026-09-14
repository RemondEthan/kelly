package com.mordor.kelly.model;

import java.time.LocalDateTime;

/**
 * 聊天消息模型。
 * 使用 Java record 定义，是一个不可变数据类。
 * 表示一条聊天消息，包含消息的元数据、内容和媒体信息（如果是图片消息）。
 * 
 * 主要功能：
 * - 存储消息的唯一标识、发送者、内容、时间戳等基本信息
 * - 支持文本消息和图片消息两种类型
 * - 提供工厂方法简化图片消息的创建
 * - 不可变设计，确保线程安全
 * 
 * @param id 消息的唯一标识符，用于历史分页和消息追踪
 * @param sender 消息发送者枚举值
 * @param content 消息内容（文本消息的文本内容，图片消息的说明文字）
 * @param timestamp 消息发送时间
 * @param from 消息来源标识（可能为空字符串）
 * @param kind 消息类型枚举值（TEXT 或 IMAGE）
 * @param mediaId 媒体文件的唯一标识符（仅图片消息使用）
 * @param previewRel 预览图的相对路径（仅图片消息使用）
 * @param originalRel 原始图片的相对路径（仅图片消息使用）
 */
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
    /**
     * 简化构造函数，用于创建文本消息。
     * 自动将 from 设为空字符串，kind 设为 TEXT，mediaId/previewRel/originalRel 设为空字符串。
     * 
     * @param id 消息的唯一标识符
     * @param sender 消息发送者枚举值
     * @param content 消息内容
     * @param timestamp 消息发送时间
     */
    public Message(String id, Sender sender, String content, LocalDateTime timestamp) {
        this(id, sender, content, timestamp, "");
    }

    /**
     * 简化构造函数，用于创建带有来源的文本消息。
     * 自动将 kind 设为 TEXT，mediaId/previewRel/originalRel 设为空字符串。
     * 
     * @param id 消息的唯一标识符
     * @param sender 消息发送者枚举值
     * @param content 消息内容
     * @param timestamp 消息发送时间
     * @param from 消息来源标识
     */
    public Message(String id, Sender sender, String content, LocalDateTime timestamp, String from) {
        this(id, sender, content, timestamp, from, MessageKind.TEXT, "", "", "");
    }

    /**
     * 工厂方法，创建图片消息。
     * 自动将 kind 设为 IMAGE，并处理 null 参数（转换为空字符串）。
     * 
     * @param id 消息的唯一标识符
     * @param sender 消息发送者枚举值
     * @param caption 图片说明文字（可为 null）
     * @param timestamp 消息发送时间
     * @param from 消息来源标识（可为 null）
     * @param mediaId 媒体文件的唯一标识符（可为 null）
     * @param previewRel 预览图的相对路径（可为 null）
     * @param originalRel 原始图片的相对路径（可为 null）
     * @return 图片消息实例
     */
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

    /**
     * 检查此消息是否为图片消息。
     * 
     * @return 如果消息类型为 IMAGE 返回 true，否则返回 false
     */
    public boolean image() {
        return kind == MessageKind.IMAGE;
    }
}