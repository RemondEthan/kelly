/**
 * AI 助手消息气泡组件。
 *
 * <p>在聊天界面中渲染 AI 助手（Tars）的回复消息。支持以下内容类型的显示：
 * <ul>
 *   <li><b>思考过程</b> - 可折叠的 AI 内部推理过程（THINKING 块）</li>
 *   <li><b>工具调用</b> - AI 调用外部工具的卡片（TOOL 块）</li>
 *   <li><b>文本回复</b> - 流式/完成后的 Markdown 渲染（TEXT 块）</li>
 *   <li><b>待办提醒</b> - 格式化的待办提醒列表</li>
 * </ul>
 *
 * <p>流式渲染：
 * <ul>
 *   <li>消息创建时绑定到消息的 blocks 列表和 streaming 属性</li>
 *   <li>当新块到达或内容变化时，自动重新渲染气泡内容</li>
 *   <li>流式过程中显示省略号占位符，完成后渲染 Markdown</li>
 * </ul>
 *
 * <p>布局结构：
 * <pre>
 *   HBox (root)
 *   ├── AvatarView (头像)
 *   └── VBox (列)
 *       ├── Label (名称)
 *       ├── VBox (内容)
 *       │   ├── thinkingBox (思考块，可折叠)
 *       │   ├── ToolCallCard (工具调用卡片)
 *       │   └── MarkdownView / SelectableTextFlow (文本内容)
 *       └── Label (时间戳)
 * </pre>
 *
 * @see MessageBlock
 * @see AssistantMessage
 * @see ToolCallCard
 */
package com.mordor.kelly.kelsy.ui;

import com.mordor.kelly.kelsy.model.AssistantMessage;
import com.mordor.kelly.kelsy.model.MessageBlock;
import com.mordor.kelly.kelsy.todo.ReminderFormat;
import com.mordor.kelly.kelsy.todo.ReminderItem;
import com.mordor.kelly.kelsy.ui.markdown.MarkdownRenderer;
import com.mordor.kelly.kelsy.ui.markdown.MarkdownView;
import com.mordor.kelly.model.RoomMember;
import com.mordor.kelly.model.Sender;
import com.mordor.kelly.ui.AvatarView;
import com.mordor.kelly.ui.chat.SelectableTextFlow;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/** tars 助手气泡：思考块、工具调用、流式/完成后的 Markdown。 */
public class AssistantBubble extends HBox {

    /** 时间戳格式化器 */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 气泡最大宽度的可观察值（响应窗口大小变化） */
    private final ObservableValue<? extends Number> maxBubbleWidth;

    /** 思考块可见性的可观察值（用户可切换显示/隐藏） */
    private final ObservableValue<Boolean> thinkingVisible;

    /** Kelsy 头像图片 */
    private final Image kelsyPhoto;

    /** 工作空间链接点击回调（打开知识库文件） */
    private final Consumer<String> onWorkspaceLink;

    /**
     * 创建助手消息气泡。
     *
     * @param msg              助手消息数据
     * @param assistantName    助手显示名称
     * @param maxBubbleWidth   气泡最大宽度（可观察值）
     * @param thinkingVisible  思考块是否可见（可观察值）
     * @param kelsyPhoto       助手头像图片
     * @param onWorkspaceLink  工作空间链接点击回调
     */
    public AssistantBubble(AssistantMessage msg, String assistantName,
                           ObservableValue<? extends Number> maxBubbleWidth,
                           ObservableValue<Boolean> thinkingVisible,
                           Image kelsyPhoto,
                           Consumer<String> onWorkspaceLink) {
        super(4);
        this.maxBubbleWidth = maxBubbleWidth;
        this.thinkingVisible = thinkingVisible;
        this.kelsyPhoto = kelsyPhoto;
        this.onWorkspaceLink = onWorkspaceLink == null ? path -> {
        } : onWorkspaceLink;
        setFillHeight(false);
        setPadding(new Insets(2, 4, 2, 4));
        renderSide(msg, assistantName == null || assistantName.isBlank()
                ? RoomMember.SECRETARY_NAME : assistantName);
    }

    /**
     * 渲染气泡的完整布局（左侧头像 + 右侧内容）。
     */
    private void renderSide(AssistantMessage msg, String name) {
        setAlignment(Pos.TOP_LEFT);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("bubble-name");

        AvatarView avatar = new AvatarView(name, kelsyPhoto, false, 28);

        Label time = new Label(DATE_TIME.format(msg.timestamp()));
        time.getStyleClass().add("bubble-time");

        VBox body = new VBox(4);
        body.setMinWidth(0);
        fillBody(body, msg);
        // 绑定消息状态变化：流式接收中或块列表变化时重新渲染
        msg.streamingProperty().addListener((obs, o, n) -> fillBody(body, msg));
        msg.blocks().addListener((ListChangeListener<MessageBlock>) c -> fillBody(body, msg));
        for (MessageBlock block : msg.blocks()) {
            if (block.kind() == MessageBlock.Kind.TOOL) {
                block.openPathProperty().addListener((obs, o, n) -> fillBody(body, msg));
            }
        }

        VBox col = new VBox(2, nameLabel, body, time);
        col.setMinWidth(0);
        col.setAlignment(Pos.TOP_LEFT);

        HBox row = new HBox(6, avatar, col);
        row.setMinWidth(0);
        row.setAlignment(Pos.TOP_LEFT);
        getChildren().add(row);
    }

    /**
     * 填充气泡内容区域。
     * 根据消息块的类型和状态，渲染对应的内容组件。
     */
    private void fillBody(VBox body, AssistantMessage msg) {
        body.getChildren().clear();
        // 流式接收中且无内容时显示省略号
        if (msg.blocks().isEmpty() && msg.streamingProperty().get()) {
            body.getChildren().add(styled(textLabel("…")));
            return;
        }
        for (MessageBlock block : msg.blocks()) {
            if (block.kind() == MessageBlock.Kind.THINKING) {
                // 思考块：有内容或正在接收时显示
                if (!block.content().isBlank() || block.streamingProperty().get()) {
                    body.getChildren().add(thinkingBox(block));
                }
                continue;
            }
            if (block.kind() == MessageBlock.Kind.TOOL) {
                // 工具调用块：显示工具调用卡片
                ToolCallCard card = new ToolCallCard(
                        block.toolName(),
                        block.openPathProperty().get(),
                        onWorkspaceLink);
                bindBubbleWidth(card);
                body.getChildren().add(card);
                continue;
            }
            // 文本块：流式中显示纯文本，完成后渲染 Markdown
            boolean streaming = msg.streamingProperty().get() || block.streamingProperty().get();
            if (streaming || msg.sender() == Sender.SYSTEM) {
                Region label = textLabelFor(block);
                body.getChildren().add(styled(label));
            } else {
                // 尝试解析为待办提醒格式
                var reminder = ReminderFormat.parse(block.content());
                if (reminder.isPresent()) {
                    body.getChildren().add(styled(reminderBox(reminder.get())));
                } else {
                    body.getChildren().add(styled(markdownOrPlain(block.content())));
                }
            }
        }
    }

    /**
     * 渲染待办提醒列表。
     */
    private Region reminderBox(List<ReminderItem> items) {
        SelectableTextFlow summary = SelectableTextFlow.forText("还有 " + items.size() + " 条待办待处理");
        VBox box = new VBox(6, summary);
        for (ReminderItem item : items) {
            SelectableTextFlow title = SelectableTextFlow.forText(item.title());
            title.setMinWidth(0);
            String due = "截止 " + item.due();
            if (item.overdue()) {
                due += "  已逾期";
            }
            SelectableTextFlow meta = SelectableTextFlow.forText(due);
            meta.getStyleClass().add("todo-reminder-due");
            Hyperlink open = new Hyperlink("打开");
            open.setOnAction(e -> onWorkspaceLink.accept(item.relativePath()));
            HBox titleRow = new HBox(8, title, open);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(title, Priority.ALWAYS);
            VBox card = new VBox(2, titleRow, meta);
            card.getStyleClass().add("todo-reminder-item");
            box.getChildren().add(card);
        }
        box.getStyleClass().add("todo-reminder");
        return box;
    }

    /**
     * 渲染思考过程块（可折叠显示）。
     */
    private Region thinkingBox(MessageBlock block) {
        Label title = new Label("思考过程");
        title.getStyleClass().add("thinking-title");

        SelectableTextFlow text = SelectableTextFlow.forText(initialContent(block));
        text.getStyleClass().add("thinking-body");
        text.textProperty().bind(Bindings.createStringBinding(
                () -> initialContent(block),
                block.contentProperty(), block.streamingProperty()));

        VBox box = new VBox(4, title, text);
        box.getStyleClass().add("thinking-block");
        // 绑定可见性（用户可切换显示/隐藏思考过程）
        if (thinkingVisible != null) {
            box.visibleProperty().bind(thinkingVisible);
            box.managedProperty().bind(thinkingVisible);
        }
        bindBubbleWidth(box);
        return box;
    }

    /**
     * 创建流式文本显示组件。
     */
    private Region textLabelFor(MessageBlock block) {
        SelectableTextFlow sel = SelectableTextFlow.forText(initialContent(block));
        sel.textProperty().bind(Bindings.createStringBinding(
                () -> initialContent(block),
                block.contentProperty(), block.streamingProperty()));
        bindBubbleWidth(sel);
        return styled(sel);
    }

    /**
     * 获取块的初始内容（空内容且流式中显示省略号）。
     */
    private static String initialContent(MessageBlock block) {
        return block.content().isEmpty() && block.streamingProperty().get() ? "…" : block.content();
    }

    /**
     * 创建纯文本显示组件。
     */
    private Region textLabel(String text) {
        SelectableTextFlow sel = SelectableTextFlow.forText(text);
        bindBubbleWidth(sel);
        return styled(sel);
    }

    /**
     * 尝试将文本渲染为 Markdown，失败时回退到纯文本。
     */
    private Node markdownOrPlain(String source) {
        try {
            MarkdownView view = new MarkdownView(MarkdownRenderer.parse(source), onWorkspaceLink);
            bindBubbleWidth(view);
            return view;
        } catch (RuntimeException e) {
            return textLabel(source);
        }
    }

    /**
     * 为组件添加统一的样式类和宽度约束。
     */
    private Region styled(Node node) {
        if (node instanceof Region region) {
            region.getStyleClass().add("bubble-peer");
            bindBubbleWidth(region);
            return region;
        }
        VBox wrap = new VBox(node);
        wrap.getStyleClass().add("bubble-peer");
        bindBubbleWidth(wrap);
        return wrap;
    }

    /**
     * 将气泡宽度绑定到最大宽度可观察值。
     */
    private void bindBubbleWidth(Region bubble) {
        bubble.setMinWidth(0);
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(120, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));
    }
}
