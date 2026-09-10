package com.mordor.kelly.service;

import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.Sender;
import com.mordor.kelly.ui.chat.ChatController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHistoryTest {

    @TempDir
    Path tmp;

    @Test
    void appendThenLoadNewestPreservesOrderAndTimestamp() throws Exception {
        Path file = tmp.resolve("messages.log");
        ChatHistory history = open(file, "secret", "ROOM");
        LocalDateTime t1 = LocalDateTime.of(2026, 8, 28, 15, 16, 1);
        LocalDateTime t2 = LocalDateTime.of(2026, 8, 28, 15, 16, 2);
        history.append(msg("a", "one", t1));
        history.append(msg("b", "two", t2));

        List<Message> loaded = history.loadNewest(100);

        assertEquals(List.of("a", "b"), ids(loaded));
        assertEquals(t1, loaded.get(0).timestamp());
        assertEquals(t2, loaded.get(1).timestamp());
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(raw.contains("one"));
        assertFalse(raw.contains("two"));
        assertEquals(3, Files.readAllLines(file).size());
    }

    @Test
    void wrongPasswordDoesNotDecryptOrRewrite() throws Exception {
        Path file = tmp.resolve("messages.log");
        ChatHistory writer = open(file, "secret", "ROOM");
        writer.append(msg("a", "keep-me", LocalDateTime.of(2026, 1, 1, 0, 0)));
        String before = Files.readString(file, StandardCharsets.UTF_8);

        ChatHistory reader = new ChatHistory(file, CryptoService.forArchive("wrong", "ROOM"));
        assertFalse(reader.open());
        assertEquals(List.of(), reader.loadNewest(100));
        reader.append(msg("b", "should-not-write", LocalDateTime.of(2026, 1, 1, 0, 1)));

        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void loadOlderThanReturnsEarlierPage() {
        Path file = tmp.resolve("messages.log");
        ChatHistory history = open(file, "secret", "ROOM");
        for (int i = 1; i <= 5; i++) {
            history.append(msg(String.valueOf(i), "m" + i, LocalDateTime.of(2026, 1, 1, 0, i)));
        }

        List<Message> older = history.loadOlderThan("4", 2);

        assertEquals(List.of("2", "3"), ids(older));
    }

    @Test
    void trimsOldestWhenOverMessageCap() throws Exception {
        Path file = tmp.resolve("messages.log");
        ChatHistory history = new ChatHistory(file, CryptoService.forArchive("secret", "ROOM"), 3, 8L * 1024 * 1024);
        assertTrue(history.open());
        for (int i = 1; i <= 5; i++) {
            history.append(msg(String.valueOf(i), "m" + i, LocalDateTime.of(2026, 1, 1, 0, i)));
        }

        assertEquals(List.of("3", "4", "5"), ids(history.loadNewest(100)));
        assertEquals(4, Files.readAllLines(file).size());
    }

    @Test
    void skipsCorruptMessageLines() throws Exception {
        Path file = tmp.resolve("messages.log");
        ChatHistory history = open(file, "secret", "ROOM");
        history.append(msg("good", "ok", LocalDateTime.of(2026, 1, 1, 12, 0)));
        Files.writeString(file, Files.readString(file) + "not-valid-base64\n", StandardCharsets.UTF_8);

        List<Message> loaded = history.loadNewest(100);
        assertEquals(List.of("good"), ids(loaded));
    }

    @Test
    void differentImCodesUseDifferentDirectories() {
        Path a = ChatHistory.defaultFile(tmp, "OFFICE");
        Path b = ChatHistory.defaultFile(tmp, "FAMILY");
        assertNotEquals(a, b);
        assertEquals("messages.log", a.getFileName().toString());
        assertEquals(a.getParent().getParent(), b.getParent().getParent());
    }

    @Test
    void loadInitialOpensAndReturnsNewest() throws Exception {
        Path file = tmp.resolve("messages.log");
        ChatHistory seed = open(file, "secret", "ROOM");
        seed.append(msg("a", "one", LocalDateTime.of(2026, 3, 3, 3, 3, 3)));
        seed.close();

        ChatHistory history = new ChatHistory(file, CryptoService.forArchive("secret", "ROOM"));
        List<Message> loaded = new ArrayList<>();
        history.loadInitialAsync(loaded::addAll);
        history.flush(2, TimeUnit.SECONDS);

        assertEquals(List.of("a"), ids(loaded));
        assertTrue(history.isUnlocked());
    }

    @Test
    void offlineArchiveRoundTripSelfAndAssistant() throws Exception {
        Path file = ChatHistory.defaultFile(tmp, ChatController.OFFLINE_IM_CODE);
        ChatHistory writer = new ChatHistory(
                file,
                CryptoService.forArchive(
                        ChatController.OFFLINE_ARCHIVE_PASSWORD,
                        ChatController.OFFLINE_IM_CODE));
        assertTrue(writer.open());
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 3, 10, 0, 1);
        writer.append(new Message("s1", Sender.SELF, "@tars 你好", t1, "me"));
        writer.append(new Message("a1", Sender.ASSISTANT, "最终正文", t2, "tars"));
        writer.flush(2, TimeUnit.SECONDS);
        writer.close();

        ChatHistory reader = new ChatHistory(
                file,
                CryptoService.forArchive(
                        ChatController.OFFLINE_ARCHIVE_PASSWORD,
                        ChatController.OFFLINE_IM_CODE));
        assertTrue(reader.open());
        List<Message> loaded = reader.loadNewest(10);
        assertEquals(List.of("s1", "a1"), ids(loaded));
        assertEquals(Sender.SELF, loaded.get(0).sender());
        assertEquals(Sender.ASSISTANT, loaded.get(1).sender());
        assertEquals("最终正文", loaded.get(1).content());
        reader.close();
    }

    @Test
    void appendAsyncWritesOnBackgroundThread() throws Exception {
        Path file = tmp.resolve("messages.log");
        ChatHistory history = open(file, "secret", "ROOM");
        history.appendAsync(msg("z", "async", LocalDateTime.of(2026, 2, 2, 2, 2, 2)));
        history.flush(2, TimeUnit.SECONDS);

        assertEquals(List.of("z"), ids(history.loadNewest(10)));
    }

    private static ChatHistory open(Path file, String password, String imCode) {
        ChatHistory history = new ChatHistory(file, CryptoService.forArchive(password, imCode));
        assertTrue(history.open());
        return history;
    }

    private static Message msg(String id, String content, LocalDateTime at) {
        return new Message(id, Sender.SELF, content, at, "me");
    }

    private static List<String> ids(List<Message> messages) {
        List<String> ids = new ArrayList<>();
        for (Message m : messages) {
            ids.add(m.id());
        }
        return ids;
    }
}
