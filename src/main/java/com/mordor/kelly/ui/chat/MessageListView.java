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
 * 消息列表视图：可滚动的消息展示区域。
 *
 * <h3>渲染机制</h3>
 * <p>订阅 {@link ChatController#getMessages()} 的 {@link ListChangeListener}，
 * 当消息列表变化时自动更新 UI：
 * <ul>
 *   <li><b>追加消息</b> - 新消息添加到容器底部，自动滚动到最新</li>
 *   <li><b>删除消息</b> - 移除对应位置的子节点</li>
 *   <li><b>前置插入</b> - 加载历史消息时插入到顶部，保持当前滚动位置</li>
 * </ul>
 *
 * <h3>滚动跟随策略</h3>
 * <p>通过 {@link ScrollFollowPolicy} 区分用户滚动和程序化变化：
 * <ul>
 *   <li>用户滚到底部 → 恢复"跟随最新"模式</li>
 *   <li>用户向上滚动 → 停止自动跟随，允许查看历史</li>
 *   <li>用户滚到顶部 → 加载更早的历史消息</li>
 * </ul>
 *
 * <h3>实时助手消息</h3>
 * <p>{@link AssistantMessage} 是流式生成的助手回复，在生成过程中实时更新。
 * 通过 {@link #syncLive} 方法维护一个"活节点"，新内容到达时替换。</p>
 *
 * <h3>pinToBottom 防抖</h3>
 * <p>使用 {@link PauseTransition}（250ms）防止连续消息导致的频繁滚动跳动。
 * 三次 {@code setVvalue(1.0)} 调用确保布局完成后再钉底。</p>
 */
public class MessageListView extends ScrollPane {

    /** 消息气泡容器（VBox 纵向排列） */
    private final VBox container;
    /** 聊天控制器 */
    private final ChatController controller;
    /** 实时助手消息的 UI 节点（生成过程中持续更新） */
    private Node liveNode;
    /** 是否正在程序化钉底（防止用户滚动被误判） */
    private boolean pinning;
    /** 钉底防抖定时器：250ms 内连续 pin 只生效最后一次 */
    private final PauseTransition pinHold = new PauseTransition(Duration.millis(250));

    /**
     * 构造消息列表视图。
     *
     * @param controller 聊天控制器
     */
    public MessageListView(ChatController controller) {
        this.controller = controller;
        getStyleClass().add("message-list");

        // ScrollPane 配置：宽度适配、隐藏水平滚动条
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        // 消息气泡容器
        container = new VBox(4);  // 垂直间距 4px
        container.setPadding(new Insets(8));
        container.getStyleClass().add("message-list-container");
        container.setFillWidth(true);

        setContent(container);

        // 钉底定时器回调
        pinHold.setOnFinished(e -> {
            setVvalue(1.0);
            pinning = false;
        });

        // 初始化：加载已有消息
        for (Message m : controller.getMessages()) {
            container.getChildren().add(newBubble(m));
        }

        // 监听消息列表变化，自动更新 UI
        controller.getMessages().addListener((ListChangeListener<Message>) c -> {
            while (c.next()) {
                if (c.wasRemoved()) {
                    // 删除消息：移除对应位置的子节点
                    int from = c.getFrom();
                    container.getChildren().remove(from, from + c.getRemovedSize());
                }
                if (c.wasAdded()) {
                    // 判断是否为前置插入（加载历史消息）
                    boolean prepend = c.getFrom() == 0 && !controller.followingLatest();
                    // 前置插入时记录锚点节点，用于保持滚动位置
                    Node anchor = prepend && !container.getChildren().isEmpty()
                            ? container.getChildren().get(0)
                            : null;
                    int i = c.getFrom();
                    for (Message m : c.getAddedSubList()) {
                        container.getChildren().add(i++, newBubble(m));
                    }
                    if (prepend && anchor != null) {
                        // 前置插入：保持锚点节点可见位置
                        keepAnchored(anchor);
                    } else if (ScrollFollowPolicy.shouldPinToBottom(controller.followingLatest(), prepend)) {
                        // 追加消息：钉在底部
                        pinToBottom();
                    }
                }
            }
        });

        // 内容高度变化时自动钉底（新消息到达导致高度增加）
        container.heightProperty().addListener((obs, o, n) -> {
            if (controller.followingLatest()) {
                pinToBottom();
            }
        });

        // 监听滚动条 vvalue 变化，判断用户意图
        vvalueProperty().addListener((obs, oldV, v) -> {
            switch (ScrollFollowPolicy.onVvalue(
                    oldV.doubleValue(), v.doubleValue(), canScroll(), pinning)) {
                case LOAD_OLDER -> controller.requestOlder();     // 滚到顶部 → 加载历史
                case FOLLOW_LATEST -> controller.followLatest();  // 回到底部 → 跟随最新
                case STOP_FOLLOWING -> controller.stopFollowing(); // 向上滚动 → 停止跟随
                case NONE -> { /* 无需响应 */ }
            }
        });

        // 头像变化时重建整个列表（简单但安全的策略）
        controller.peerAvatars().addListener((MapChangeListener<Integer, Image>) c -> rebuild());
        // 实时助手消息变化时同步 UI
        controller.liveAssistantProperty().addListener((obs, o, n) -> syncLive(n));
        syncLive(controller.liveAssistantProperty().get());
    }

    /**
     * 将滚动条钉在底部（跟随最新消息）。
     *
     * <p>使用三重保险：
     * <ol>
     *   <li>立即设置 vvalue=1.0</li>
     *   <li>250ms 后再次设置（布局完成后）</li>
     *   <li>Platform.runLater 双重嵌套确保渲染管线完成</li>
     * </ol>
     */
    private void pinToBottom() {
        pinning = true;
        setVvalue(1.0);
        // 重置防抖定时器
        pinHold.stop();
        pinHold.playFromStart();
        Platform.runLater(() -> {
            setVvalue(1.0);
            Platform.runLater(() -> setVvalue(1.0));
        });
    }

    /**
     * 前置插入历史消息时保持锚点节点的可见位置。
     *
     * <p>原理：记录锚点节点在插入前的位置，插入后重新计算 vvalue
     * 使锚点保持在原来的位置。</p>
     */
    private void keepAnchored(Node anchor) {
        Platform.runLater(() -> {
            double contentH = container.getHeight();
            double viewH = getViewportBounds().getHeight();
            if (contentH > viewH) {
                setVvalue(anchor.getBoundsInParent().getMinY() / (contentH - viewH));
            }
            // 如果已经在顶部附近，继续加载更早的历史
            if (canScroll() && getVvalue() <= 0.02) {
                controller.requestOlder();
            }
        });
    }

    /**
     * 判断容器是否可滚动（内容高度 > 视口高度 + 8px 容差）。
     */
    private boolean canScroll() {
        return container.getHeight() > getViewportBounds().getHeight() + 8;
    }

    /**
     * 重建整个消息列表（头像变化时调用）。
     */
    private void rebuild() {
        container.getChildren().clear();
        liveNode = null;
        for (Message m : controller.getMessages()) {
            container.getChildren().add(newBubble(m));
        }
        syncLive(controller.liveAssistantProperty().get());
    }

    /**
     * 同步实时助手消息的 UI 节点。
     *
     * @param live 当前正在生成的助手消息，null 表示无活消息
     */
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

    /**
     * 计算气泡最大宽度：列表宽度的 70%。
     */
    private ObservableValue<? extends Number> bubbleMaxWidth() {
        return widthProperty().multiply(0.7);
    }

    /**
     * 为消息创建对应的气泡 UI 节点。
     *
     * @param m 消息数据
     * @return 消息气泡节点（MessageBubble 或 AssistantBubble）
     */
    private Node newBubble(Message m) {
        // 助手消息使用专用的 AssistantBubble
        if (m.sender() == Sender.ASSISTANT) {
            return newAssistantBubble(AssistantMessage.of(Sender.ASSISTANT, m.content()));
        }
        AppState s = controller.getState();
        String peer = (m.from() != null && !m.from().isBlank()) ? m.from() : s.peerName();
        return new MessageBubble(m, s.username(), peer, controller.avatarOf(m.from()),
                s.avatar(), bubbleMaxWidth(),
                controller.mediaFile(m.previewRel()),
                controller.mediaFile(m.originalRel()));
    }

    /**
     * 创建助手消息气泡。
     */
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
