package com.mordor.kelly.ui.chat;

import com.mordor.kelly.kelsy.KelsyRoomSettings;
import com.mordor.kelly.kelsy.KelsyRoomSettingsTest.MemoryPrefs;
import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.Sender;
import com.mordor.kelly.service.ChatHistory;
import com.mordor.kelly.service.CryptoService;
import com.mordor.kelly.service.ImClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatController 断线重连的单元测试。
 *
 * <p>验证链路状态到 UI 状态的映射：在线属性、重连中属性、系统提示文案，
 * 以及断线瞬间的成员清理。</p>
 */
class ChatControllerReconnectTest {

    @TempDir Path tmp;

    private ChatHistory history;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (history != null) {
            history.flush(2, TimeUnit.SECONDS);
            history.close();
        }
    }

    @Test
    void disconnectedStateMarksOfflineAndShowsReconnect() {
        ChatController controller = controller(false);

        controller.onEvent(new ImClient.Event.LinkStateChanged(ImClient.LinkState.DISCONNECTED));

        assertFalse(controller.onlineProperty().get(), "断开后应离线");
        assertFalse(controller.reconnectingProperty().get(), "断开后不在重连中");
    }

    @Test
    void connectingStateMarksReconnecting() {
        ChatController controller = controller(false);

        controller.onEvent(new ImClient.Event.LinkStateChanged(ImClient.LinkState.CONNECTING));

        assertFalse(controller.onlineProperty().get());
        assertTrue(controller.reconnectingProperty().get(), "连接中应标记为重连中");
    }

    @Test
    void connectedStateMarksOnline() {
        ChatController controller = controller(false);

        controller.onEvent(new ImClient.Event.LinkStateChanged(ImClient.LinkState.CONNECTING));
        controller.onEvent(new ImClient.Event.LinkStateChanged(ImClient.LinkState.CONNECTED));

        assertTrue(controller.onlineProperty().get(), "连接后应在线");
        assertFalse(controller.reconnectingProperty().get(), "连接后不在重连中");
    }

    @Test
    void stateTransitionEmitsSystemMessages() {
        ChatController controller = controller(false);

        // 真实序列：已连接 → 断开 → 重连中 → 已连接
        controller.onEvent(new ImClient.Event.LinkStateChanged(ImClient.LinkState.CONNECTED));
        controller.onEvent(new ImClient.Event.LinkStateChanged(ImClient.LinkState.DISCONNECTED));
        controller.onEvent(new ImClient.Event.LinkStateChanged(ImClient.LinkState.CONNECTING));
        controller.onEvent(new ImClient.Event.LinkStateChanged(ImClient.LinkState.CONNECTED));

        List<String> texts = systemTexts(controller);
        assertTrue(texts.stream().anyMatch(t -> t.contains("连接已断开")),
                "应有断开提示，实际：" + texts);
        assertTrue(texts.stream().anyMatch(t -> t.contains("正在重新连接")),
                "应有重连中提示，实际：" + texts);
        assertTrue(texts.stream().anyMatch(t -> t.contains("已重新连接")),
                "应有重连成功提示，实际：" + texts);
    }

    @Test
    void closedEventRecordsReasonWithoutDuplicatingMessage() {
        ChatController controller = controller(false);

        controller.onEvent(new ImClient.Event.Closed("server gone"));
        List<String> texts = systemTexts(controller);

        // Closed 事件本身不发消息，提示统一由 LinkStateChanged 触发
        assertFalse(texts.stream().anyMatch(t -> t.contains("server gone")),
                "Closed 事件不应直接发消息，实际：" + texts);
    }

    @Test
    void offlineModeHasNoReconnect() {
        ChatController controller = controller(true);

        assertTrue(controller.offlineMode(), "脱机模式应报告 offlineMode");
        // 脱机模式下重连是空操作，不应抛异常
        controller.reconnect();
        assertFalse(controller.onlineProperty().get(), "脱机模式应始终离线");
    }

    @Test
    void reconnectInOnlineModeIsSafeWithoutClient() {
        ChatController controller = controller(false);
        // state 为 null（测试构造），不应抛异常
        controller.reconnect();
    }

    private static List<String> systemTexts(ChatController controller) {
        return controller.getMessages().stream()
                .filter(m -> m.sender() == Sender.SYSTEM)
                .map(Message::content)
                .toList();
    }

    private ChatController controller(boolean offline) {
        KelsyRoomSettings settings = new KelsyRoomSettings(new MemoryPrefs());
        String imCode = offline ? ChatController.OFFLINE_IM_CODE : "ROOM";
        history = new ChatHistory(
                tmp.resolve("messages.log"),
                CryptoService.forArchive("pw", imCode));
        assertTrue(history.open());
        return new ChatController(
                imCode,
                "me",
                history,
                text -> {
                },
                settings,
                null,
                offline);
    }
}
