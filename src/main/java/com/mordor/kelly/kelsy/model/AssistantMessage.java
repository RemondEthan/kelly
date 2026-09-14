/**
 * AI 助手消息数据模型。
 *
 * <p>表示 AI 助手（如 Tars）的一条回复消息。每条消息包含：
 * <ul>
 *   <li><b>id</b> - 唯一标识符（UUID），用于消息追踪和 UI 绑定</li>
 *   <li><b>sender</b> - 发送者标识（系统或用户）</li>
 *   <li><b>timestamp</b> - 创建时间戳</li>
 *   <li><b>blocks</b> - 消息块列表（文本、工具调用、思考过程等）</li>
 *   <li><b>streaming</b> - 是否正在流式接收中</li>
 * </ul>
 *
 * <p>流式消息处理：
 * <ul>
 *   <li>消息创建时通过 {@link #streaming(Sender)} 创建流式消息</li>
 *   <li>通过 {@link #append(String)} 逐块追加文本内容</li>
 *   <li>通过 {@link #addTool(int, String, String)} 插入工具调用块</li>
 *   <li>通过 {@link #finish()} 标记消息接收完成</li>
 * </ul>
 *
 * <p>消息块（{@link MessageBlock}）支持三种类型：
 * <ul>
 *   <li><b>TEXT</b> - 文本内容（AI 的回复文本）</li>
 *   <li><b>TOOL</b> - 工具调用（如读取文件、搜索知识库）</li>
 *   <li><b>THINKING</b> - 思考过程（AI 的内部推理过程，可折叠显示）</li>
 * </ul>
 *
 * <p>JavaFX 绑定：所有属性均为 JavaFX Observable 类型，
 * 支持在 UI 中直接绑定，实现响应式更新。
 */
package com.mordor.kelly.kelsy.model;

import com.mordor.kelly.model.Sender;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalDateTime;
import java.util.UUID;

public final class AssistantMessage {

    /** 消息唯一标识符（UUID 格式） */
    private final String id = UUID.randomUUID().toString();

    /** 发送者标识（系统消息或用户消息） */
    private final Sender sender;

    /** 消息创建时间戳 */
    private final LocalDateTime timestamp = LocalDateTime.now();

    /** 消息块列表（TEXT/TOOL/THINKING），ObservableList 支持 UI 自动更新 */
    private final ObservableList<MessageBlock> blocks = FXCollections.observableArrayList();

    /** 流式接收状态：true 表示消息仍在接收中 */
    private final BooleanProperty streaming = new SimpleBooleanProperty(false);

    /**
     * 私有构造函数，通过工厂方法创建实例。
     *
     * @param sender 消息发送者
     */
    private AssistantMessage(Sender sender) {
        this.sender = sender;
    }

    /**
     * 创建包含指定文本内容的完整消息（非流式）。
     *
     * @param sender  发送者
     * @param content 文本内容
     * @return 新的消息实例
     */
    public static AssistantMessage of(Sender sender, String content) {
        AssistantMessage m = new AssistantMessage(sender);
        m.blocks.add(MessageBlock.text(content));
        return m;
    }

    /**
     * 创建流式消息（初始为空，后续通过 append 追加内容）。
     *
     * @param sender 发送者
     * @return 新的流式消息实例
     */
    public static AssistantMessage streaming(Sender sender) {
        AssistantMessage m = new AssistantMessage(sender);
        m.streaming.set(true);
        return m;
    }

    /**
     * 确保消息中存在一个文本块，返回最后一个文本块。
     * 如果不存在，自动创建一个新的文本块并添加到末尾。
     *
     * @return 文本类型的 MessageBlock
     */
    public MessageBlock ensureTextBlock() {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            MessageBlock b = blocks.get(i);
            if (b.kind() == MessageBlock.Kind.TEXT) {
                return b;
            }
        }
        MessageBlock created = MessageBlock.text();
        blocks.add(created);
        return created;
    }

    /**
     * 确保消息中存在一个思考块，返回第一个思考块。
     * 如果不存在，自动创建并插入到消息开头（思考过程通常显示在最前面）。
     *
     * @return 思考类型的 MessageBlock
     */
    public MessageBlock ensureThinkingBlock() {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            MessageBlock b = blocks.get(i);
            if (b.kind() == MessageBlock.Kind.THINKING) {
                return b;
            }
        }
        MessageBlock created = MessageBlock.thinking();
        blocks.add(0, created);
        return created;
    }

    /**
     * 追加文本增量到消息的文本块中。
     * 用于流式响应：每次收到新文本片段时调用。
     *
     * @param delta 文本增量片段
     */
    public void append(String delta) {
        ensureTextBlock().append(delta);
    }

    /**
     * 追加思考过程增量。
     * 用于流式响应：AI 的内部推理过程。
     *
     * @param delta 思考过程增量片段
     */
    public void appendThinking(String delta) {
        ensureThinkingBlock().append(delta);
    }

    /**
     * 标记所有思考块为完成状态。
     * 思考过程结束后调用。
     */
    public void finishThinking() {
        for (MessageBlock b : blocks) {
            if (b.kind() == MessageBlock.Kind.THINKING) {
                b.finish();
            }
        }
    }

    /**
     * 在指定位置插入工具调用块。
     *
     * <p>工具调用块显示 AI 调用了哪些工具（如读取文件、搜索知识库），
     * 以及工具的参数预览。用户可以通过工具调用卡片查看详细信息。
     *
     * @param index      插入位置（0 = 开头，跳过思考块）
     * @param name       工具名称（如 "read_file"、"memory_search"）
     * @param argsPreview 工具参数预览文本
     */
    public void addTool(int index, String name, String argsPreview) {
        int at = Math.max(0, Math.min(index, blocks.size()));
        // 如果 index 为 0，跳过思考块找到第一个非思考块位置
        if (index == 0) {
            while (at < blocks.size() && blocks.get(at).kind() == MessageBlock.Kind.THINKING) {
                at++;
            }
        }
        blocks.add(at, MessageBlock.tool(name, argsPreview));
    }

    /**
     * 标记消息接收完成。
     * 将所有块标记为完成状态，并将流式状态设为 false。
     */
    public void finish() {
        for (MessageBlock b : blocks) {
            b.finish();
        }
        streaming.set(false);
    }

    /** 获取消息唯一标识符 */
    public String id() {
        return id;
    }

    /** 获取消息发送者 */
    public Sender sender() {
        return sender;
    }

    /** 获取消息创建时间戳 */
    public LocalDateTime timestamp() {
        return timestamp;
    }

    /** 获取消息块列表（ObservableList，支持 UI 绑定） */
    public ObservableList<MessageBlock> blocks() {
        return blocks;
    }

    /**
     * 获取消息的纯文本内容（仅 TEXT 类型块的内容，忽略工具调用和思考过程）。
     *
     * @return 拼接后的纯文本
     */
    public String content() {
        StringBuilder sb = new StringBuilder();
        for (MessageBlock b : blocks) {
            if (b.kind() == MessageBlock.Kind.TEXT) {
                sb.append(b.content());
            }
        }
        return sb.toString();
    }

    /**
     * 获取文本内容的 JavaFX StringProperty，用于 UI 绑定。
     * 直接绑定到最后一个文本块的内容属性。
     *
     * @return 文本内容属性
     */
    public StringProperty contentProperty() {
        return ensureTextBlock().contentProperty();
    }

    /**
     * 获取流式接收状态的 JavaFX BooleanProperty，用于 UI 绑定。
     *
     * @return 流式状态属性
     */
    public BooleanProperty streamingProperty() {
        return streaming;
    }
}
