package com.mordor.kelly.ui.chat;

import com.mordor.kelly.common.Diagnostics;
import com.mordor.kelly.kelsy.service.KnowledgeStore;
import com.mordor.kelly.kelsy.todo.TodoReminderService;
import com.mordor.kelly.kelsy.ui.knowledge.KnowledgePane;
import com.mordor.kelly.model.AppState;
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
 * 聊天面板：整窗背景图 + 左侧成员列表 + 顶栏 / 消息 / 输入。
 */
public class ChatPane extends StackPane {

    private final ChatController controller;
    private final BorderPane ui;
    private final BorderPane chat;
    private KnowledgePane knowledge;

    public ChatPane(AppState state) {
        long t0 = System.nanoTime();
        getStylesheets().add(
                ChatPane.class.getResource("chat.css").toExternalForm());
        getStyleClass().add("chat-root");
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        controller = new ChatController(state);
        Diagnostics.log("ui", "controller %dms", Diagnostics.elapsedMs(t0));

        ui = new BorderPane();
        ui.getStyleClass().add("chat-ui");
        ui.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        InputBar input = new InputBar(
                controller::send,
                controller::secretaryNickname,
                controller::sendImage,
                controller.getMembers(),
                (member, name) -> member.isKelsy()
                        ? controller.avatarOfSecretary()
                        : controller.avatarOf(name));
        chat = new BorderPane();
        chat.setCenter(new MessageListView(controller));
        ui.setTop(new ChatHeader(state, controller));
        ui.setLeft(new RoomMemberList(controller, input::insertMention));
        ui.setBottom(input);
        applyCenter();
        controller.kelsyEnabledProperty().addListener((obs, o, n) -> applyCenter());
        controller.knowledgeVisibleProperty().addListener((obs, o, n) -> applyCenter());

        getChildren().addAll(wallpaper(), ui);
        Diagnostics.log("ui", "ChatPane constructed %dms", Diagnostics.elapsedMs(t0));
    }

    public void close() {
        controller.close();
    }

    private static Region wallpaper() {
        Region wallpaper = new Region();
        wallpaper.setMouseTransparent(true);
        wallpaper.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        long t0 = System.nanoTime();
        Image img = new Image(ChatPane.class.getResource("bg-chat.png").toExternalForm());
        Diagnostics.log("ui", "bg-chat.png loaded %dms error=%s w=%.0f",
                Diagnostics.elapsedMs(t0), img.isError(), img.getWidth());
        if (img.isError()) {
            Diagnostics.warn("ui", "bg-chat.png failed to load");
        }
        wallpaper.setBackground(new Background(new BackgroundImage(
                img,
                BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, false, false, false, true))));
        return wallpaper;
    }

    /**
     * 会话中途启用/关闭 kelsy 时重装中间区。
     * OpenJFX SplitPane 仍会给 unmanaged 子节点留槽，隐藏知识库时必须把节点从 items 里拿掉。
     */
    private void applyCenter() {
        if (ui.getCenter() instanceof SplitPane old) {
            old.getItems().clear();
        }

        KnowledgeStore store = controller.knowledgeStore();
        if (!controller.kelsyEnabled() || store == null) {
            controller.setOnCitationSources(null);
            controller.setOnOpenKnowledge(null);
            controller.setOnRefreshKnowledge(null);
            knowledge = null;
            ui.setCenter(chat);
            return;
        }

        if (knowledge == null) {
            knowledge = new KnowledgePane(store, controller.memoryWarnProperty());
            controller.setOnCitationSources(knowledge::setSources);
            controller.setOnOpenKnowledge(knowledge::open);
            controller.setOnRefreshKnowledge(knowledge::refresh);
            controller.startReminders(new TodoReminderService(store.workspace()));
        }

        if (controller.knowledgeVisibleProperty().get()) {
            knowledge.setMinWidth(240);
            knowledge.setPrefWidth(320);
            SplitPane split = new SplitPane();
            split.getItems().setAll(chat, knowledge);
            split.setDividerPositions(0.70);
            ui.setCenter(split);
        } else {
            ui.setCenter(chat);
        }
    }
}
