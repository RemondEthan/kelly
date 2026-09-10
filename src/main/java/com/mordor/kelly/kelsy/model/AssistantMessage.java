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

    private final String id = UUID.randomUUID().toString();
    private final Sender sender;
    private final LocalDateTime timestamp = LocalDateTime.now();
    private final ObservableList<MessageBlock> blocks = FXCollections.observableArrayList();
    private final BooleanProperty streaming = new SimpleBooleanProperty(false);

    private AssistantMessage(Sender sender) {
        this.sender = sender;
    }

    public static AssistantMessage of(Sender sender, String content) {
        AssistantMessage m = new AssistantMessage(sender);
        m.blocks.add(MessageBlock.text(content));
        return m;
    }

    public static AssistantMessage streaming(Sender sender) {
        AssistantMessage m = new AssistantMessage(sender);
        m.streaming.set(true);
        return m;
    }

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

    public void append(String delta) {
        ensureTextBlock().append(delta);
    }

    public void appendThinking(String delta) {
        ensureThinkingBlock().append(delta);
    }

    public void finishThinking() {
        for (MessageBlock b : blocks) {
            if (b.kind() == MessageBlock.Kind.THINKING) {
                b.finish();
            }
        }
    }

    public void addTool(int index, String name, String argsPreview) {
        int at = Math.max(0, Math.min(index, blocks.size()));
        if (index == 0) {
            while (at < blocks.size() && blocks.get(at).kind() == MessageBlock.Kind.THINKING) {
                at++;
            }
        }
        blocks.add(at, MessageBlock.tool(name, argsPreview));
    }

    public void finish() {
        for (MessageBlock b : blocks) {
            b.finish();
        }
        streaming.set(false);
    }

    public String id() {
        return id;
    }

    public Sender sender() {
        return sender;
    }

    public LocalDateTime timestamp() {
        return timestamp;
    }

    public ObservableList<MessageBlock> blocks() {
        return blocks;
    }

    public String content() {
        StringBuilder sb = new StringBuilder();
        for (MessageBlock b : blocks) {
            if (b.kind() == MessageBlock.Kind.TEXT) {
                sb.append(b.content());
            }
        }
        return sb.toString();
    }

    public StringProperty contentProperty() {
        return ensureTextBlock().contentProperty();
    }

    public BooleanProperty streamingProperty() {
        return streaming;
    }
}
