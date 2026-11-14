package com.mordor.kelly.model;

/**
 * 消息类型枚举。
 * 定义聊天消息的两种基本类型。
 * 用于区分文本消息和图片消息，在消息处理和显示时根据类型采取不同的策略。
 */
public enum MessageKind { 
    /**
     * 文本消息类型。
     * 表示纯文本内容的消息，内容存储在 Message 的 content 字段中。
     */
    TEXT, 
    
    /**
     * 图片消息类型。
     * 表示包含图片的消息，媒体信息存储在 Message 的 mediaId、previewRel 和 originalRel 字段中。
     * content 字段可能包含图片的说明文字。
     */
    IMAGE 
}