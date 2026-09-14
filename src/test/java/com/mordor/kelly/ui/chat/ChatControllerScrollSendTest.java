package com.mordor.kelly.ui.chat;

import com.mordor.kelly.kelsy.KelsyPaths;
import com.mordor.kelly.kelsy.KelsyRoomSettings;
import com.mordor.kelly.kelsy.KelsyRoomSettingsTest.MemoryPrefs;
import com.mordor.kelly.kelsy.KelsyRuntime;
import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.Sender;
import com.mordor.kelly.service.ChatHistory;
import com.mordor.kelly.service.CryptoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerScrollSendTest {

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
    void sendAfterLoadingOlderJumpsToContinuousLatest() throws Exception {
        int total = ChatHistory.MEMORY_CAP + ChatHistory.PAGE_SIZE;
        history = new ChatHistory(
                tmp.resolve("messages.log"),
                CryptoService.forArchive("pw", "ROOM"));
        assertTrue(history.open());
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        for (int i = 0; i < total; i++) {
            history.append(new Message(
                    String.valueOf(i),
                    Sender.SELF,
                    "m" + i,
                    t0.plusSeconds(i),
                    "me"));
        }

        ChatController c = controller(new ArrayList<>());
        List<Message> newest = history.loadNewest(ChatHistory.MEMORY_CAP);
        c.showSnapshot(newest);
        assertEquals(String.valueOf(total - ChatHistory.MEMORY_CAP), newest.get(0).id());
        assertEquals(String.valueOf(total - 1), newest.get(newest.size() - 1).id());

        List<Message> older = history.loadOlderThan(newest.get(0).id(), ChatHistory.PAGE_SIZE);
        c.applyOlderPage(older);
        assertEquals("0", c.getMessages().get(0).id());
        assertNotEquals(String.valueOf(total - 1), c.getMessages().get(c.getMessages().size() - 1).id());

        assertTrue(c.send("after-scroll").accepted());
        history.flush(2, TimeUnit.SECONDS);

        List<Message> shown = List.copyOf(c.getMessages());
        assertEquals("after-scroll", shown.get(shown.size() - 1).content());
        assertNotEquals("0", shown.get(0).id());
        for (int i = 1; i < shown.size() - 1; i++) {
            int prev = Integer.parseInt(shown.get(i - 1).id());
            int cur = Integer.parseInt(shown.get(i).id());
            assertEquals(prev + 1, cur, "gap between " + prev + " and " + cur);
        }
        assertEquals(String.valueOf(total - 1), shown.get(shown.size() - 2).id());
    }

    private ChatController controller(List<String> peer) throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp);
        Files.createDirectories(paths.config().getParent());
        KelsyRoomSettings settings = new KelsyRoomSettings(new MemoryPrefs());
        runtime = KelsyRuntime.open(paths, "me", cfg -> null);
        return new ChatController("ROOM", "me", history, peer::add, settings, runtime, false);
    }
}
