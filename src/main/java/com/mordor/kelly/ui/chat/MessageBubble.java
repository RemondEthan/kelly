package com.mordor.kelly.ui.chat;

import com.mordor.kelly.model.Message;
import com.mordor.kelly.ui.AvatarView;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MessageBubble extends HBox {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String STYLE_SELF = "bubble-self";
    private static final String STYLE_PEER = "bubble-peer";
    private static final String STYLE_SYS = "bubble-system";
    private static final String STYLE_NAME = "bubble-name";
    private static final String STYLE_TIME = "bubble-time";
    private static final String STYLE_SELECTABLE = "bubble-text-selectable";

    private final ObservableValue<? extends Number> maxBubbleWidth;

    public MessageBubble(Message msg, String myName, String peerName, Image peerAvatar, Image myAvatar,
                         ObservableValue<? extends Number> maxBubbleWidth) {
        super(4);
        this.maxBubbleWidth = maxBubbleWidth;
        setFillHeight(false);
        setPadding(new Insets(2, 4, 2, 4));

        switch (msg.sender()) {
            case SELF -> renderSide(msg, displayName(myName, "我"), myAvatar, true);
            case PEER, ASSISTANT -> renderSide(msg, displayName(peerName, "对方"), peerAvatar, false);
            case SYSTEM -> renderSystem(msg);
        }
    }

    private void renderSide(Message msg, String name, Image photo, boolean self) {
        setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add(STYLE_NAME);
        nameLabel.setWrapText(false);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setAlignment(sideMetaAlignment(self));
        nameLabel.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(80, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));

        AvatarView avatar = new AvatarView(name, photo, self, 28);

        Label time = new Label(formatTime(msg.timestamp()));
        time.getStyleClass().add(STYLE_TIME);
        time.setAlignment(sideMetaAlignment(self));
        time.maxWidthProperty().bind(nameLabel.maxWidthProperty());

        TextFlow bubble = buildBubble(msg, self ? STYLE_SELF : STYLE_PEER);

        VBox col = new VBox(2, nameLabel, bubble, time);
        col.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        HBox row = self ? new HBox(6, col, avatar) : new HBox(6, avatar, col);
        row.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        getChildren().add(row);
    }

    private void renderSystem(Message msg) {
        setAlignment(Pos.CENTER);
        TextFlow bubble = buildBubble(msg, STYLE_SYS);
        getChildren().add(bubble);
    }

    private TextFlow buildBubble(Message msg, String bubbleStyle) {
        SelectableTextFlow selectable = SelectableTextFlow.forText(msg.content());
        bindBubbleWidth(selectable);
        selectable.getStyleClass().add(bubbleStyle);
        selectable.getStyleClass().add(STYLE_SELECTABLE);
        return selectable;
    }

    private void bindBubbleWidth(Region bubble) {
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(120, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));
    }

    static String formatTime(LocalDateTime timestamp) {
        return DATE_TIME.format(timestamp);
    }

    static Pos sideMetaAlignment(boolean self) {
        return self ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT;
    }

    private static String displayName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }
}
