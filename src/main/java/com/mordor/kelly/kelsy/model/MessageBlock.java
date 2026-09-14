/**
 * 消息块：AI 回复的最小组成单元。
 *
 * <p>AI 助手的一条回复（{@link AssistantMessage}）由多个消息块组成，
 * 每个块代表回复中的一个逻辑部分。支持三种类型：
 *
 * <ul>
 *   <li><b>TEXT</b> - 文本内容块：AI 的回复文本，支持流式追加</li>
 *   <li><b>TOOL</b> - 工具调用块：AI 调用外部工具（如读取文件、搜索知识库）的记录</li>
 *   <li><b>THINKING</b> - 思考过程块：AI 的内部推理过程，可折叠显示</li>
 * </ul>
 *
 * <p>流式处理：
 * <ul>
 *   <li>创建时 {@code streaming} 属性为 true</li>
 *   <li>通过 {@link #append(String)} 逐片追加内容</li>
 *   <li>调用 {@link #finish()} 标记完成</li>
 * </ul>
 *
 * <p>JavaFX 集成：
 * <ul>
 *   <li>所有属性均为 JavaFX Observable 类型</li>
 *   <li>支持直接在 FXML 或代码中绑定</li>
 *   <li>内容变化会自动通知 UI 更新</li>
 * </ul>
 */
package com.mordor.kelly.kelsy.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class MessageBlock {

    /**
     * 消息块类型枚举。
     */
    public enum Kind {
        /** 文本内容块 - AI 的回复文本 */
        TEXT,
        /** 工具调用块 - AI 调用外部工具的记录 */
        TOOL,
        /** 思考过程块 - AI 的内部推理过程 */
        THINKING
    }

    /** 消息块类型 */
    private final Kind kind;

    /** 文本内容属性（TEXT 和 THINKING 类型使用） */
    private final StringProperty content = new SimpleStringProperty("");

    /** 流式接收状态：true 表示内容仍在追加中 */
    private final BooleanProperty streaming = new SimpleBooleanProperty(false);

    /** 工具名称（仅 TOOL 类型有值，如 "read_file"、"memory_search"） */
    private final String toolName;

    /** 工具参数预览文本（仅 TOOL 类型有值，显示工具调用的参数摘要） */
    private final StringProperty argsPreview = new SimpleStringProperty("");

    /** 关联的文件路径（仅 TOOL 类型使用，点击"打开"时跳转到该路径） */
    private final StringProperty openPath = new SimpleStringProperty("");

    /**
     * 私有构造函数。
     *
     * @param kind       消息块类型
     * @param toolName   工具名称（TOOL 类型必填，其他类型为 null）
     * @param argsPreview 工具参数预览
     */
    private MessageBlock(Kind kind, String toolName, String argsPreview) {
        this.kind = kind;
        this.toolName = toolName;
        this.argsPreview.set(argsPreview == null ? "" : argsPreview);
    }

    /**
     * 创建一个空的流式文本块（初始内容为空，streaming=true）。
     *
     * @return 新的文本块
     */
    public static MessageBlock text() {
        MessageBlock b = new MessageBlock(Kind.TEXT, null, "");
        b.streaming.set(true);
        return b;
    }

    /**
     * 创建包含指定内容的完成文本块（streaming=false）。
     *
     * @param content 文本内容
     * @return 新的文本块
     */
    public static MessageBlock text(String content) {
        MessageBlock b = new MessageBlock(Kind.TEXT, null, "");
        b.content.set(content == null ? "" : content);
        return b;
    }

    /**
     * 创建工具调用块。
     *
     * @param name       工具名称
     * @param argsPreview 工具参数预览
     * @return 新的工具调用块
     */
    public static MessageBlock tool(String name, String argsPreview) {
        return new MessageBlock(Kind.TOOL, name, argsPreview);
    }

    /**
     * 创建空的流式思考块（初始内容为空，streaming=true）。
     *
     * @return 新的思考块
     */
    public static MessageBlock thinking() {
        MessageBlock b = new MessageBlock(Kind.THINKING, null, "");
        b.streaming.set(true);
        return b;
    }

    /** 获取消息块类型 */
    public Kind kind() {
        return kind;
    }

    /** 获取工具名称（仅 TOOL 类型有值） */
    public String toolName() {
        return toolName;
    }

    /** 获取工具参数预览文本 */
    public String argsPreview() {
        return argsPreview.get();
    }

    /**
     * 追加工具参数增量。
     * 用于流式响应：逐步接收工具调用参数。
     *
     * @param delta 参数增量片段
     */
    public void appendArgs(String delta) {
        if (delta != null && !delta.isEmpty()) {
            argsPreview.set(argsPreview.get() + delta);
        }
    }

    /** 获取文本内容 */
    public String content() {
        return content.get();
    }

    /** 获取文本内容的 JavaFX StringProperty（支持 UI 绑定） */
    public StringProperty contentProperty() {
        return content;
    }

    /** 获取关联文件路径的 JavaFX StringProperty（支持 UI 绑定） */
    public StringProperty openPathProperty() {
        return openPath;
    }

    /** 获取流式状态的 JavaFX BooleanProperty（支持 UI 绑定） */
    public BooleanProperty streamingProperty() {
        return streaming;
    }

    /**
     * 追加文本增量到内容中。
     * 用于流式响应：每次收到新文本片段时调用。
     *
     * @param delta 文本增量片段
     */
    public void append(String delta) {
        if (delta != null && !delta.isEmpty()) {
            content.set(content.get() + delta);
        }
    }

    /**
     * 标记此块为完成状态。
     * 流式接收结束后调用。
     */
    public void finish() {
        streaming.set(false);
    }
}
