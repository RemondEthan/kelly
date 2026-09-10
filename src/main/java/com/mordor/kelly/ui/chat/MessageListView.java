package com.mordor.kelly.ui.chat;

import com.mordor.kelly.kelsy.model.AssistantMessage;
import com.mordor.kelly.kelsy.ui.AssistantBubble;
import com.mordor.kelly.model.AppState;
import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.Sender;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.util.Duration;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;

/**
 * 消息列表:一个可滚动的 VBox,每个消息是一个 MessageBubble。
 *
 * 渲染来源:订阅 ChatController.getMessages() 的 ListChangeListener。
 * 处理追加、删除和上翻插入；仅在跟在最新时钉住底部。
 *
 * 背景由 ChatPane 铺在底层，本列表保持透明。
 */
public class MessageListView extends ScrollPane {

    private final VBox container;
    private final ChatController controller;
    private Node liveNode;
    private boolean pinning;
    private final PauseTransition pinHold = new PauseTransition(Duration.millis(250));

    public MessageListView(ChatController controller) {
        this.controller = controller;
        getStyleClass().add("message-list");

        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        container = new VBox(4);
        container.setPadding(new Insets(8));
        container.getStyleClass().add("message-list-container");
        container.setFillWidth(true);

        setContent(container);
        pinHold.setOnFinished(e -> {
            setVvalue(1.0);
            pinning = false;
        });

        for (Message m : controller.getMessages()) {
            container.getChildren().add(newBubble(m));
        }

        controller.getMessages().addListener((ListChangeListener<Message>) c -> {
            while (c.next()) {
                if (c.wasRemoved()) {
                    int from = c.getFrom();
                    container.getChildren().remove(from, from + c.getRemovedSize());
                }
                if (c.wasAdded()) {
                    boolean prepend = c.getFrom() == 0 && !controller.followingLatest();
                    Node anchor = prepend && !container.getChildren().isEmpty()
                            ? container.getChildren().get(0)
                            : null;
                    int i = c.getFrom();
                    for (Message m : c.getAddedSubList()) {
                        container.getChildren().add(i++, newBubble(m));
                    }
                    if (prepend && anchor != null) {
                        keepAnchored(anchor);
                    } else if (ScrollFollowPolicy.shouldPinToBottom(controller.followingLatest(), prepend)) {
                        pinToBottom();
                    }
                }
            }
        });

        container.heightProperty().addListener((obs, o, n) -> {
            if (controller.followingLatest()) {
                pinToBottom();
            }
        });
        vvalueProperty().addListener((obs, oldV, v) -> {
            switch (ScrollFollowPolicy.onVvalue(
                    oldV.doubleValue(), v.doubleValue(), canScroll(), pinning)) {
                case LOAD_OLDER -> controller.requestOlder();
                case FOLLOW_LATEST -> controller.followLatest();
                case STOP_FOLLOWING -> controller.stopFollowing();
                case NONE -> {
                }
            }
        });
        controller.peerAvatars().addListener((MapChangeListener<Integer, Image>) c -> rebuild());
        controller.liveAssistantProperty().addListener((obs, o, n) -> syncLive(n));
        syncLive(controller.liveAssistantProperty().get());
    }

    private void pinToBottom() {
        pinning = true;
        setVvalue(1.0);
        // 连续发消息会叠多个 pin；旧定时器若先结束会提前 pinning=false，布局抖动就被当成用户滚动。
        pinHold.stop();
        pinHold.playFromStart();
        Platform.runLater(() -> {
            setVvalue(1.0);
            Platform.runLater(() -> setVvalue(1.0));
        });
    }

    private void keepAnchored(Node anchor) {
        Platform.runLater(() -> {
            double contentH = container.getHeight();
            double viewH = getViewportBounds().getHeight();
            if (contentH > viewH) {
                setVvalue(anchor.getBoundsInParent().getMinY() / (contentH - viewH));
            }
            if (canScroll() && getVvalue() <= 0.02) {
                controller.requestOlder();
            }
        });
    }

    private boolean canScroll() {
        return container.getHeight() > getViewportBounds().getHeight() + 8;
    }

    private void rebuild() {
        container.getChildren().clear();
        liveNode = null;
        for (Message m : controller.getMessages()) {
            container.getChildren().add(newBubble(m));
        }
        syncLive(controller.liveAssistantProperty().get());
    }

    private void syncLive(AssistantMessage live) {
        if (liveNode != null) {
            container.getChildren().remove(liveNode);
            liveNode = null;
        }
        if (live != null) {
            liveNode = newAssistantBubble(live);
            container.getChildren().add(liveNode);
            if (controller.followingLatest()) {
                pinToBottom();
            }
        }
    }

    private ObservableValue<? extends Number> bubbleMaxWidth() {
        return widthProperty().multiply(0.7);
    }

    private Node newBubble(Message m) {
        if (m.sender() == Sender.ASSISTANT) {
            return newAssistantBubble(AssistantMessage.of(Sender.ASSISTANT, m.content()));
        }
        AppState s = controller.getState();
        String peer = (m.from() != null && !m.from().isBlank()) ? m.from() : s.peerName();
        return new MessageBubble(m, s.username(), peer, controller.avatarOf(m.from()),
                s.avatar(), bubbleMaxWidth());
    }

    private AssistantBubble newAssistantBubble(AssistantMessage msg) {
        return new AssistantBubble(
                msg,
                controller.secretaryNickname(),
                bubbleMaxWidth(),
                controller.thinkingVisibleProperty(),
                controller.avatarOfSecretary(),
                controller::openKnowledge);
    }
}
