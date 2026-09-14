package com.mordor.kelly.ui.chat;

import com.mordor.kelly.common.Diagnostics;
import com.mordor.kelly.kelsy.service.KnowledgeStore;
import com.mordor.kelly.kelsy.todo.TodoReminderService;
import com.mordor.kelly.kelsy.ui.knowledge.KnowledgePane;
import com.mordor.kelly.model.AppState;
import com.mordor.kelly.ui.chat.font.ChatFontApplier;
import com.mordor.kelly.ui.chat.font.ChatFontSettingsService;
import javafx.scene.control.SplitPane;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * 聊天主面板：整合所有聊天子组件的顶层容器。
 *
 * <h3>场景图结构</h3>
 * <pre>
 *   StackPane (本类, chat-root)
 *   ├── Region (wallpaper, 背景图，鼠标穿透)
 *   └── BorderPane (chat-ui)
 *       ├── Top:     ChatHeader (标题栏)
 *       ├── Left:    RoomMemberList (成员列表)
 *       ├── Bottom:  InputBar (输入栏)
 *       └── Center:  BorderPane (chat)
 *           ├── Center: MessageListView (消息列表)
 *           └── Right:  [可选] KnowledgePane (知识库，SplitPane 分割)
 * </pre>
 *
 * <h3>JavaFX 布局容器说明</h3>
 * <ul>
 *   <li>{@link StackPane} - 层叠布局，子节点重叠放置（背景 + UI 叠放）</li>
 *   <li>{@link BorderPane} - 边缘布局，分 Top/Bottom/Left/Right/Center 五个区域</li>
 *   <li>{@link SplitPane} - 分割面板，可拖拽分割条调整左右区域比例</li>
 * </ul>
 *
 * <h3>知识库面板动态切换</h3>
 * <p>启用/禁用 Kelsy 或切换知识库可见性时，需要重建 Center 区域。
 * 原因是 OpenJFX 的 SplitPane 会为 unmanaged 子节点保留布局空间，
 * 必须从 items 中完全移除才能正确隐藏。</p>
 */
public class ChatPane extends StackPane {

    /** 聊天业务控制器 */
    private final ChatController controller;
    /** 字体设置持久化服务 */
    private final ChatFontSettingsService fontService;
    /** 顶层 UI 容器（BorderPane 布局） */
    private final BorderPane ui;
    /** 聊天区域容器（消息列表 + 可选知识库） */
    private final BorderPane chat;
    /** 知识库面板实例（Kelsy 启用时创建） */
    private KnowledgePane knowledge;

    /**
     * 构造聊天主面板。
     *
     * <p>初始化流程：
     * 1. 加载 CSS 样式表
     * 2. 创建 ChatController（业务逻辑）
     * 3. 组装所有子组件（Header/MemberList/InputBar/MessageListView）
     * 4. 监听 Kelsy 启用/知识库可见性变化，动态调整布局
     * 5. 加载背景图并应用字体设置</p>
     *
     * @param state 应用状态
     */
    public ChatPane(AppState state) {
        long t0 = System.nanoTime();
        // 加载聊天专用 CSS 样式表
        getStylesheets().add(
                ChatPane.class.getResource("chat.css").toExternalForm());
        getStyleClass().add("chat-root");
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);  // 填满父容器

        controller = new ChatController(state);
        fontService = new ChatFontSettingsService();
        Diagnostics.log("ui", "controller %dms", Diagnostics.elapsedMs(t0));

        // 创建顶层 BorderPane
        ui = new BorderPane();
        ui.getStyleClass().add("chat-ui");
        ui.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        // 输入栏：传入发送回调、秘书昵称、图片发送、成员列表、头像查询
        InputBar input = new InputBar(
                controller::send,
                controller::secretaryNickname,
                controller::sendImage,
                controller.getMembers(),
                (member, name) -> member.isKelsy()
                        ? controller.avatarOfSecretary()
                        : controller.avatarOf(name));

        // 聊天区域：消息列表居中
        chat = new BorderPane();
        chat.setCenter(new MessageListView(controller));

        // 组装 BorderPane 的各个区域
        ui.setTop(new ChatHeader(state, controller, this, fontService));
        ui.setLeft(new RoomMemberList(controller, input::insertMention));
        ui.setBottom(input);
        applyCenter();

        // 监听 Kelsy 启用/知识库可见性变化，动态调整中间区域
        controller.kelsyEnabledProperty().addListener((obs, o, n) -> applyCenter());
        controller.knowledgeVisibleProperty().addListener((obs, o, n) -> applyCenter());

        // 背景图 + UI 叠放（StackPane 层叠）
        getChildren().addAll(wallpaper(), ui);
        // 应用字体设置
        ChatFontApplier.apply(this, fontService.load());
        Diagnostics.log("ui", "ChatPane constructed %dms", Diagnostics.elapsedMs(t0));
    }

    /**
     * 关闭聊天面板，释放资源。
     */
    public void close() {
        controller.close();
    }

    /**
     * 创建背景图层。
     *
     * <p>使用 {@link BackgroundImage} 将 PNG 图片设为平铺背景。
     * {@code setMouseTransparent(true)} 让背景图不接收鼠标事件，
     * 事件会穿透到下层的 UI 组件。</p>
     *
     * @return 配置好背景图的 Region
     */
    private static Region wallpaper() {
        Region wallpaper = new Region();
        wallpaper.setMouseTransparent(true);  // 鼠标事件穿透
        wallpaper.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        long t0 = System.nanoTime();
        Image img = new Image(ChatPane.class.getResource("bg-chat.png").toExternalForm());
        Diagnostics.log("ui", "bg-chat.png loaded %dms error=%s w=%.0f",
                Diagnostics.elapsedMs(t0), img.isError(), img.getWidth());
        if (img.isError()) {
            Diagnostics.warn("ui", "bg-chat.png failed to load");
        }
        // BackgroundImage：居中显示、不重复、适应容器大小
        wallpaper.setBackground(new Background(new BackgroundImage(
                img,
                BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO,
                        false, false, false, true))));
        return wallpaper;
    }

    /**
     * 动态调整中间区域：根据 Kelsy 启用状态和知识库可见性切换布局。
     *
     * <p>三种状态：
     * <ul>
     *   <li>Kelsy 未启用 → 只显示聊天区域</li>
     *   <li>Kelsy 启用但知识库隐藏 → 只显示聊天区域</li>
     *   <li>Kelsy 启用且知识库显示 → SplitPane 分割：聊天 70% + 知识库 30%</li>
     * </ul>
     *
     * <p>注意：必须先清空旧 SplitPane 的 items，否则 OpenJFX 会为已移除的子节点保留布局空间。</p>
     */
    private void applyCenter() {
        // 清理旧的 SplitPane
        if (ui.getCenter() instanceof SplitPane old) {
            old.getItems().clear();
        }

        KnowledgeStore store = controller.knowledgeStore();
        // Kelsy 未启用或无知识库时，只显示聊天区域
        if (!controller.kelsyEnabled() || store == null) {
            controller.setOnCitationSources(null);
            controller.setOnOpenKnowledge(null);
            controller.setOnRefreshKnowledge(null);
            knowledge = null;
            ui.setCenter(chat);
            return;
        }

        // 首次启用 Kelsy 时创建知识库面板
        if (knowledge == null) {
            knowledge = new KnowledgePane(store, controller.memoryWarnProperty());
            controller.setOnCitationSources(knowledge::setSources);
            controller.setOnOpenKnowledge(knowledge::open);
            controller.setOnRefreshKnowledge(knowledge::refresh);
            // 启动待办提醒服务
            controller.startReminders(new TodoReminderService(store.workspace()));
        }

        // 知识库可见时用 SplitPane 分割显示
        if (controller.knowledgeVisibleProperty().get()) {
            knowledge.setMinWidth(240);
            knowledge.setPrefWidth(320);
            SplitPane split = new SplitPane();
            split.getItems().setAll(chat, knowledge);
            split.setDividerPositions(0.70);  // 聊天区域占 70%
            ui.setCenter(split);
        } else {
            ui.setCenter(chat);
        }
    }
}
