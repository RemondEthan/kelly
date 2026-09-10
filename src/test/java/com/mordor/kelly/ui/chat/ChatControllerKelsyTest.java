package com.mordor.kelly.ui.chat;

import com.mordor.kelly.kelsy.KelsyPaths;
import com.mordor.kelly.kelsy.KelsyRoomSettings;
import com.mordor.kelly.kelsy.KelsyRoomSettingsTest.MemoryPrefs;
import com.mordor.kelly.kelsy.KelsyRuntime;
import com.mordor.kelly.kelsy.service.AssistantService;
import com.mordor.kelly.kelsy.todo.ReminderBatch;
import com.mordor.kelly.kelsy.todo.ReminderSlot;
import com.mordor.kelly.kelsy.todo.TodoCard;
import com.mordor.kelly.kelsy.todo.TodoReminderService;
import com.mordor.kelly.kelsy.todo.TodoStatus;
import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.RoomMember;
import com.mordor.kelly.model.Sender;
import com.mordor.kelly.service.ChatHistory;
import com.mordor.kelly.service.CryptoService;
import com.mordor.kelly.service.ImClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerKelsyTest {

    @TempDir Path tmp;

    private ChatHistory history;
    private KelsyRuntime runtime;

    @AfterEach
    void tearDown() throws Exception {
        if (history != null) {
            history.flush(2, TimeUnit.SECONDS);
            history.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void mentionDoesNotCallPeerWhenEnabled() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true);
        assertTrue(c.send("@tars 你好").accepted());
        assertTrue(peer.isEmpty());
        assertEquals(List.of("你好"), asked);
        var last = c.getMessages().getLast();
        assertEquals(Sender.SELF, last.sender());
        assertEquals("@tars 你好", last.content());
    }

    @Test
    void disabledMentionGoesToPeer() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true);
        assertTrue(c.send("@tars 你好").accepted());
        assertEquals(List.of("@tars 你好"), peer);
    }

    @Test
    void busyMentionRejectedKeepsNoPeerCall() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true);
        c.send("@tars 第一问");
        asked.clear();
        ChatController.SendResult busy = c.send("@tars 第二问");
        assertFalse(busy.accepted());
        assertEquals(ChatController.BUSY_HINT, busy.hint());
        assertTrue(asked.isEmpty());
        assertTrue(peer.isEmpty());
    }

    @Test
    void peerStillSendsWhileBusy() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), true, true);
        c.send("@tars 第一问");
        assertTrue(c.send("普通").accepted());
        assertEquals(List.of("普通"), peer);
    }

    @Test
    void enableAddsKelsyWithoutChangingHumanCount() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), false, true);
        assertEquals("ROOM", c.imCode());
        assertEquals(1, c.humanCountProperty().get());
        assertTrue(c.getMembers().stream().noneMatch(RoomMember::isKelsy));
        c.enableKelsy("");
        assertTrue(c.getMembers().stream().anyMatch(RoomMember::isKelsy));
        assertEquals(2, c.getMembers().size());
        assertEquals(1, c.humanCountProperty().get());
        assertEquals(RoomMember.SECRETARY_NAME, RoomMember.kelsy().username());
        assertNull(c.avatarOf(RoomMember.SECRETARY_NAME));
        assertNull(c.avatarOf("kelsy"));
        c.disableKelsy();
        assertTrue(c.getMembers().stream().noneMatch(RoomMember::isKelsy));
        assertEquals(1, c.getMembers().size());
        assertEquals(1, c.humanCountProperty().get());
    }

    @Test
    void enabledCtorIncludesKelsyInMembersNotHumanCount() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        assertEquals(2, c.getMembers().size());
        assertEquals(1, c.humanCountProperty().get());
    }

    @Test
    void customNickMentionAsksAndTarsGoesPeer() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, false, true);
        c.enableKelsy("", "Ada");
        assertEquals("Ada", c.secretaryNickname());
        assertTrue(c.getMembers().stream().anyMatch(m -> m.isKelsy() && "Ada".equals(m.username())));
        assertTrue(c.send("@Ada 你好").accepted());
        assertEquals(List.of("你好"), asked);
        assertTrue(peer.isEmpty());
        asked.clear();
        assertTrue(c.send("@tars 你好").accepted());
        assertEquals(List.of("@tars 你好"), peer);
    }

    @Test
    void offlinePeerIsRejectedWithoutCallingSender() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true, true);
        assertEquals(ChatController.OFFLINE_IM_CODE, c.imCode());
        ChatController.SendResult r = c.send("普通");
        assertFalse(r.accepted());
        assertEquals(ChatController.OFFLINE_REJECT_HINT, r.hint());
        assertTrue(peer.isEmpty());
        assertTrue(c.getMessages().isEmpty());
    }

    @Test
    void offlineMentionWithoutTarsIsRejected() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true, true);
        ChatController.SendResult r = c.send("@tars 你好");
        assertFalse(r.accepted());
        assertEquals(ChatController.OFFLINE_REJECT_HINT, r.hint());
        assertTrue(peer.isEmpty());
    }

    @Test
    void offlineHistoryLoadSkipsWaitingForPeer() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), false, true, true);
        c.onHistoryLoaded(List.of());
        assertTrue(c.getMessages().isEmpty());
    }

    @Test
    void offlineAskDoesNotCallPeer() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true, true);
        assertTrue(c.send("@tars 你好").accepted());
        assertEquals(List.of("你好"), asked);
        assertTrue(peer.isEmpty());
        ChatController.SendResult plain = c.send("普通");
        assertFalse(plain.accepted());
        assertEquals(ChatController.OFFLINE_REJECT_HINT, plain.hint());
        assertTrue(peer.isEmpty());
    }

    @Test
    void peerLeftRemovesMemberKeepsRememberedId() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), false, true);
        c.onEvent(new ImClient.Event.PeerJoined(200001, "Bob"));
        assertTrue(c.getMembers().stream().anyMatch(m -> m.userId() == 200001));
        assertEquals(Integer.valueOf(200001), c.rememberedUserId("Bob"));
        c.peerAvatars().put(200001, null);
        c.onEvent(new ImClient.Event.PeerLeft(200001, "Bob"));
        assertTrue(c.getMembers().stream().noneMatch(m -> m.userId() == 200001));
        assertEquals(Integer.valueOf(200001), c.rememberedUserId("Bob"));
        assertTrue(c.peerAvatars().containsKey(200001));
    }

    @Test
    void secretaryAvatarIgnoresPeerNamedTars() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        c.onEvent(new ImClient.Event.PeerJoined(200002, "tars"));
        assertNull(c.avatarOf("tars"));
        assertNull(c.avatarOfSecretary());
        assertEquals(Integer.valueOf(200002), c.rememberedUserId("tars"));
    }

    @Test
    void askWithoutToolsOpensCitedTodo() throws Exception {
        List<List<String>> sources = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true,
                false, (text, handler) -> {
                    handler.onTextDelta("有一张待办。\n来源：knowledge/todos/2026-09-07-给晓慧发kelly代码.md");
                    handler.onComplete();
                });
        c.setOnCitationSources(sources::add);
        c.setOnOpenKnowledge(opened::add);
        assertTrue(c.send("@tars 我最近有什么待办项？").accepted());
        assertEquals(List.of("knowledge/todos/2026-09-07-给晓慧发kelly代码.md"), sources.getLast());
        assertEquals("knowledge/todos/2026-09-07-给晓慧发kelly代码.md", opened.getLast());
        assertTrue(c.knowledgeVisibleProperty().get());
    }

    @Test
    void todoAskOpensDiskCardsWhenReplyHasNoPath() throws Exception {
        List<List<String>> sources = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true,
                false, (text, handler) -> {
                    handler.onTextDelta("有一张待办：给晓慧发 kelly 代码。");
                    handler.onComplete();
                });
        Path userRoot = c.knowledgeStore().workspace();
        Files.createDirectories(userRoot.resolve("knowledge/todos"));
        Files.writeString(userRoot.resolve("knowledge/todos/2026-09-07-给晓慧发kelly代码.md"),
                "- 截止：2026-09-07\n- 状态：open\n- 标题：给晓慧发 kelly 代码\n");
        c.setOnCitationSources(sources::add);
        c.setOnOpenKnowledge(opened::add);
        assertTrue(c.send("@tars 我最近有什么重要待办？").accepted());
        assertEquals(List.of("knowledge/todos/2026-09-07-给晓慧发kelly代码.md"), sources.getLast());
        assertEquals("knowledge/todos/2026-09-07-给晓慧发kelly代码.md", opened.getLast());
        assertTrue(c.knowledgeVisibleProperty().get());
    }

    @Test
    void meetingAskOpensDiskCardsWhenReplyHasNoPath() throws Exception {
        List<List<String>> sources = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true,
                false, (text, handler) -> {
                    handler.onTextDelta("会上定了先申请再发货。");
                    handler.onComplete();
                });
        Path userRoot = c.knowledgeStore().workspace();
        Files.createDirectories(userRoot.resolve("knowledge/meetings"));
        Files.writeString(userRoot.resolve("knowledge/meetings/2026-09-04-客户XX-交付licence.md"),
                "# 会议 · 客户XX · 交付 licence\n- 结论：先申请再发货\n");
        c.setOnCitationSources(sources::add);
        c.setOnOpenKnowledge(opened::add);
        assertTrue(c.send("@tars 我最近有什么会议？").accepted());
        assertEquals(List.of("knowledge/meetings/2026-09-04-客户XX-交付licence.md"), sources.getLast());
        assertEquals("knowledge/meetings/2026-09-04-客户XX-交付licence.md", opened.getLast());
    }

    @Test
    void peopleAskOpensMatchingCard() throws Exception {
        List<List<String>> sources = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true,
                false, (text, handler) -> {
                    handler.onTextDelta("张三是合作方。");
                    handler.onComplete();
                });
        Path userRoot = c.knowledgeStore().workspace();
        Files.createDirectories(userRoot.resolve("knowledge/people"));
        Files.writeString(userRoot.resolve("knowledge/people/张三.md"), "# 张三\n- 角色：合作方\n");
        c.setOnCitationSources(sources::add);
        c.setOnOpenKnowledge(opened::add);
        assertTrue(c.send("@tars 张三是谁").accepted());
        assertEquals(List.of("knowledge/people/张三.md"), sources.getLast());
        assertEquals("knowledge/people/张三.md", opened.getLast());
    }

    @Test
    void reminderPostsAssistantAndOpensFirstTodo() throws Exception {
        List<List<String>> sources = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        c.setOnCitationSources(sources::add);
        c.setOnOpenKnowledge(opened::add);
        var batch = new ReminderBatch(
                List.of(
                        new TodoCard("申请", LocalDate.of(2026, 9, 10), TodoStatus.OPEN,
                                "knowledge/todos/2026-09-10-申请.md"),
                        new TodoCard("周报", LocalDate.of(2026, 9, 12), TodoStatus.OPEN,
                                "knowledge/todos/2026-09-12-周报.md")),
                EnumSet.of(ReminderSlot.LOGIN));
        c.applyReminder(batch, LocalDate.of(2026, 9, 4));
        Message last = c.getMessages().getLast();
        assertEquals(Sender.ASSISTANT, last.sender());
        assertTrue(last.content().startsWith("还有 2 条待办待处理"));
        assertEquals(List.of(
                "knowledge/todos/2026-09-10-申请.md",
                "knowledge/todos/2026-09-12-周报.md"), sources.getLast());
        assertEquals("knowledge/todos/2026-09-10-申请.md", opened.getLast());
        assertTrue(c.knowledgeVisibleProperty().get());
    }

    @Test
    void reminderDefersPaneWhileAskingThenAppliesIfNoRetrieval() throws Exception {
        List<List<String>> sources = new ArrayList<>();
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        c.setOnCitationSources(sources::add);
        c.setAsking(true);
        var batch = new ReminderBatch(
                List.of(new TodoCard("申请", LocalDate.of(2026, 9, 10), TodoStatus.OPEN,
                        "knowledge/todos/2026-09-10-申请.md")),
                EnumSet.of(ReminderSlot.TEN));
        c.applyReminder(batch, LocalDate.of(2026, 9, 4));
        assertEquals(Sender.ASSISTANT, c.getMessages().getLast().sender());
        assertTrue(sources.isEmpty());
        c.finishAskWithoutRetrieval();
        assertEquals(List.of("knowledge/todos/2026-09-10-申请.md"), sources.getLast());
    }

    @Test
    void fireRemindersAtUsesLedgerCatchUp() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        Path userRoot = c.knowledgeStore().workspace();
        Files.createDirectories(userRoot.resolve("knowledge/todos"));
        Files.writeString(userRoot.resolve("knowledge/todos/2026-09-04-当天.md"),
                "- 截止：2026-09-04\n- 状态：open\n- 标题：当天\n");
        c.attachReminders(new TodoReminderService(userRoot));
        c.fireRemindersAt(LocalDateTime.of(2026, 9, 4, 10, 1));
        assertEquals(Sender.ASSISTANT, c.getMessages().getLast().sender());
        c.fireRemindersAt(LocalDateTime.of(2026, 9, 4, 10, 2));
        long assistant = c.getMessages().stream().filter(m -> m.sender() == Sender.ASSISTANT).count();
        assertEquals(1, assistant);
        c.stopReminders();
    }

    private ChatController controller(List<String> peer, List<String> asked,
                                      boolean enabled, boolean configured) throws Exception {
        return controller(peer, asked, enabled, configured, false, null);
    }

    private ChatController controller(List<String> peer, List<String> asked,
                                      boolean enabled, boolean configured,
                                      boolean offline) throws Exception {
        return controller(peer, asked, enabled, configured, offline, null);
    }

    private ChatController controller(List<String> peer, List<String> asked,
                                      boolean enabled, boolean configured,
                                      boolean offline,
                                      BiConsumer<String, AssistantService.ReplyHandler> onChat)
            throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp);
        Files.createDirectories(paths.config().getParent());
        if (configured) {
            Files.writeString(paths.config(), "{\"model\":{\"apiKey\":\"sk-test\"}}");
        }
        String thatImCode = offline ? ChatController.OFFLINE_IM_CODE : "ROOM";
        KelsyRoomSettings settings = new KelsyRoomSettings(new MemoryPrefs());
        if (enabled) {
            settings.enable(thatImCode, "", RoomMember.SECRETARY_NAME);
        }
        AssistantService fake = new AssistantService() {
            @Override
            public void chat(String text, ReplyHandler handler) {
                asked.add(text);
                if (onChat != null) {
                    onChat.accept(text, handler);
                }
            }

            @Override
            public void close() {
            }
        };
        runtime = KelsyRuntime.open(paths, "me", cfg -> fake);
        history = new ChatHistory(
                tmp.resolve("messages.log"),
                CryptoService.forArchive("pw", thatImCode));
        assertTrue(history.open());
        return new ChatController(
                thatImCode,
                "me",
                history,
                peer::add,
                settings,
                runtime,
                offline);
    }
}
