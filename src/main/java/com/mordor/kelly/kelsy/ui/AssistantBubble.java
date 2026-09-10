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

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObservableValue<? extends Number> maxBubbleWidth;
    private final ObservableValue<Boolean> thinkingVisible;
    private final Image kelsyPhoto;
    private final Consumer<String> onWorkspaceLink;

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

    private void fillBody(VBox body, AssistantMessage msg) {
        body.getChildren().clear();
        if (msg.blocks().isEmpty() && msg.streamingProperty().get()) {
            body.getChildren().add(styled(textLabel("…")));
            return;
        }
        for (MessageBlock block : msg.blocks()) {
            if (block.kind() == MessageBlock.Kind.THINKING) {
                if (!block.content().isBlank() || block.streamingProperty().get()) {
                    body.getChildren().add(thinkingBox(block));
                }
                continue;
            }
            if (block.kind() == MessageBlock.Kind.TOOL) {
                ToolCallCard card = new ToolCallCard(
                        block.toolName(),
                        block.openPathProperty().get(),
                        onWorkspaceLink);
                bindBubbleWidth(card);
                body.getChildren().add(card);
                continue;
            }
            boolean streaming = msg.streamingProperty().get() || block.streamingProperty().get();
            if (streaming || msg.sender() == Sender.SYSTEM) {
                Region label = textLabelFor(block);
                body.getChildren().add(styled(label));
            } else {
                var reminder = ReminderFormat.parse(block.content());
                if (reminder.isPresent()) {
                    body.getChildren().add(styled(reminderBox(reminder.get())));
                } else {
                    body.getChildren().add(styled(markdownOrPlain(block.content())));
                }
            }
        }
    }

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
        if (thinkingVisible != null) {
            box.visibleProperty().bind(thinkingVisible);
            box.managedProperty().bind(thinkingVisible);
        }
        bindBubbleWidth(box);
        return box;
    }

    private Region textLabelFor(MessageBlock block) {
        SelectableTextFlow sel = SelectableTextFlow.forText(initialContent(block));
        sel.textProperty().bind(Bindings.createStringBinding(
                () -> initialContent(block),
                block.contentProperty(), block.streamingProperty()));
        bindBubbleWidth(sel);
        return styled(sel);
    }

    private static String initialContent(MessageBlock block) {
        return block.content().isEmpty() && block.streamingProperty().get() ? "…" : block.content();
    }

    private Region textLabel(String text) {
        SelectableTextFlow sel = SelectableTextFlow.forText(text);
        bindBubbleWidth(sel);
        return styled(sel);
    }

    private Node markdownOrPlain(String source) {
        try {
            MarkdownView view = new MarkdownView(MarkdownRenderer.parse(source), onWorkspaceLink);
            bindBubbleWidth(view);
            return view;
        } catch (RuntimeException e) {
            return textLabel(source);
        }
    }

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

    private void bindBubbleWidth(Region bubble) {
        bubble.setMinWidth(0);
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(120, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));
    }
}
