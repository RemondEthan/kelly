package com.mordor.kelly.ui.chat;

import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.MessageKind;
import com.mordor.kelly.ui.AvatarView;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 消息气泡组件：根据发送者类型渲染不同的消息样式。
 *
 * <h3>场景图结构</h3>
 * <pre>
 *   HBox (本类)
 *   ├── [自己消息] HBox(6): VBox(col) + AvatarView
 *   ├── [对方消息] HBox(6): AvatarView + VBox(col)
 *   │   └── VBox(col, spacing=2):
 *   │       ├── Label (名字)
 *   │       ├── TextFlow (消息内容/图片)
 *   │       └── Label (时间)
 *   └── [系统消息] TextFlow (居中)
 * </pre>
 *
 * <h3>三种发送者类型</h3>
 * <ul>
 *   <li>{@code SELF} - 自己发的消息，头像在右</li>
 *   <li>{@code PEER / ASSISTANT} - 对方/助手消息，头像在左</li>
 *   <li>{@code SYSTEM} - 系统消息，居中显示无头像</li>
 * </ul>
 *
 * <h3>JavaFX 属性绑定</h3>
 * <p>气泡最大宽度通过 {@link Bindings#createDoubleBinding} 与父容器宽度绑定（70%），
 * 窗口缩放时气泡宽度自动调整。</p>
 */
public class MessageBubble extends HBox {

    /** 时间格式化器 */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // CSS 样式类名常量
    private static final String STYLE_SELF = "bubble-self";
    private static final String STYLE_PEER = "bubble-peer";
    private static final String STYLE_SYS = "bubble-system";
    private static final String STYLE_NAME = "bubble-name";
    private static final String STYLE_TIME = "bubble-time";
    private static final String STYLE_SELECTABLE = "bubble-text-selectable";

    /** 气泡最大宽度的观察值（绑定到父容器宽度的 70%） */
    private final ObservableValue<? extends Number> maxBubbleWidth;
    /** 预览图文件路径 */
    private final Path previewFile;
    /** 原图文件路径 */
    private final Path originalFile;

    /**
     * 简化构造（纯文本消息，无图片）。
     */
    public MessageBubble(Message msg, String myName, String peerName, Image peerAvatar, Image myAvatar,
                         ObservableValue<? extends Number> maxBubbleWidth) {
        this(msg, myName, peerName, peerAvatar, myAvatar, maxBubbleWidth, null, null);
    }

    /**
     * 完整构造：根据消息类型渲染气泡。
     *
     * @param msg            消息数据
     * @param myName         当前用户名
     * @param peerName       对方用户名
     * @param peerAvatar     对方头像
     * @param myAvatar       自己头像
     * @param maxBubbleWidth 气泡最大宽度（观察值，用于属性绑定）
     * @param previewFile    图片预览文件
     * @param originalFile   图片原图文件
     */
    public MessageBubble(Message msg, String myName, String peerName, Image peerAvatar, Image myAvatar,
                         ObservableValue<? extends Number> maxBubbleWidth,
                         Path previewFile, Path originalFile) {
        super(4);  // 水平间距 4px
        this.maxBubbleWidth = maxBubbleWidth;
        this.previewFile = previewFile;
        this.originalFile = originalFile;
        setFillHeight(false);
        setPadding(new Insets(2, 4, 2, 4));

        // 根据发送者类型选择渲染策略
        switch (msg.sender()) {
            case SELF -> renderSide(msg, displayName(myName, "我"), myAvatar, true);
            case PEER, ASSISTANT -> renderSide(msg, displayName(peerName, "对方"), peerAvatar, false);
            case SYSTEM -> renderSystem(msg);
        }
    }

    /**
     * 渲染左侧或右侧的消息气泡（自己/对方/助手）。
     *
     * <p>布局结构：名字 + 气泡内容 + 时间 → 纵向排列；整体 + 头像 → 樍向排列。
     * 自己的消息右对齐（头像在右），对方的消息左对齐（头像在左）。</p>
     */
    private void renderSide(Message msg, String name, Image photo, boolean self) {
        setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        // 名字标签
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add(STYLE_NAME);
        nameLabel.setWrapText(false);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);  // 超长名字显示省略号
        nameLabel.setAlignment(sideMetaAlignment(self));
        // 绑定最大宽度到 maxBubbleWidth
        nameLabel.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(80, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));

        // 头像组件
        AvatarView avatar = new AvatarView(name, photo, self, 28);

        // 时间标签
        Label time = new Label(formatTime(msg.timestamp()));
        time.getStyleClass().add(STYLE_TIME);
        time.setAlignment(sideMetaAlignment(self));
        time.maxWidthProperty().bind(nameLabel.maxWidthProperty());

        // 气泡内容：文本消息用 TextFlow，图片消息用 ImageView
        Region bubble = msg.kind() == MessageKind.IMAGE
                ? buildImageBubble(msg, self ? STYLE_SELF : STYLE_PEER)
                : buildBubble(msg, self ? STYLE_SELF : STYLE_PEER);

        // 纵向排列：名字 + 气泡 + 时间
        VBox col = new VBox(2, nameLabel, bubble, time);
        col.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        // 横向排列：自己消息 col+avatar，对方消息 avatar+col
        HBox row = self ? new HBox(6, col, avatar) : new HBox(6, avatar, col);
        row.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        getChildren().add(row);
    }

    /**
     * 渲染系统消息（居中显示，无头像）。
     */
    private void renderSystem(Message msg) {
        setAlignment(Pos.CENTER);
        TextFlow bubble = buildBubble(msg, STYLE_SYS);
        getChildren().add(bubble);
    }

    /**
     * 构建文本消息气泡。
     *
     * @param msg        消息数据
     * @param bubbleStyle CSS 样式类名
     * @return 可选择文本的 TextFlow
     */
    private TextFlow buildBubble(Message msg, String bubbleStyle) {
        SelectableTextFlow selectable = SelectableTextFlow.forText(msg.content());
        bindBubbleWidth(selectable);
        selectable.getStyleClass().add(bubbleStyle);
        selectable.getStyleClass().add(STYLE_SELECTABLE);
        return selectable;
    }

    /**
     * 构建图片消息气泡：缩略图 + 可选文字说明。
     *
     * <p>点击图片会打开 {@link ImageViewer} 独立窗口查看原图。</p>
     */
    private VBox buildImageBubble(Message msg, String bubbleStyle) {
        VBox box = new VBox(4);
        box.getStyleClass().add(bubbleStyle);
        box.getStyleClass().add("bubble-image");
        bindBubbleWidth(box);

        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        // 图片最大宽度 = 气泡宽度 - 边距
        view.fitWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(80, maxBubbleWidth.getValue().doubleValue() - 24),
                maxBubbleWidth));
        // 优先显示预览图，没有则显示原图
        Path shown = previewFile != null && Files.isRegularFile(previewFile) ? previewFile : originalFile;
        if (shown != null && Files.isRegularFile(shown)) {
            view.setImage(new Image(shown.toUri().toString(), true));  // 异步加载
        }
        // 点击图片打开查看器
        view.setOnMouseClicked(e -> ImageViewer.show(previewFile, originalFile));
        box.getChildren().add(view);

        // 如果有文字说明（图片消息的 caption），在图片下方显示
        if (msg.content() != null && !msg.content().isBlank()) {
            SelectableTextFlow caption = SelectableTextFlow.forText(msg.content());
            caption.getStyleClass().add(STYLE_SELECTABLE);
            box.getChildren().add(caption);
        }
        return box;
    }

    /**
     * 绑定气泡最大宽度到 maxBubbleWidth 的 100%（最小 120px）。
     */
    private void bindBubbleWidth(Region bubble) {
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(120, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));
    }

    /**
     * 格式化时间戳为 "yyyy-MM-dd HH:mm:ss" 格式。
     */
    static String formatTime(LocalDateTime timestamp) {
        return DATE_TIME.format(timestamp);
    }

    /**
     * 根据消息方向返回元信息对齐方式。
     */
    static Pos sideMetaAlignment(boolean self) {
        return self ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT;
    }

    /**
     * 获取显示名称，空值时使用回退名。
     */
    private static String displayName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }
}
