package com.mordor.kelly.ui.chat;

import com.mordor.kelly.kelsy.KelsyPaths;
import com.mordor.kelly.kelsy.KelsyRoomSettings;
import com.mordor.kelly.kelsy.KelsyRoomSettingsTest.MemoryPrefs;
import com.mordor.kelly.kelsy.KelsyRuntime;
import com.mordor.kelly.kelsy.service.AssistantService;
import com.mordor.kelly.model.MessageKind;
import com.mordor.kelly.model.RoomMember;
import com.mordor.kelly.model.Sender;
import com.mordor.kelly.service.ChatHistory;
import com.mordor.kelly.service.CryptoService;
import com.mordor.kelly.service.ImageDraft;
import com.mordor.kelly.service.ImageWire;
import com.mordor.kelly.service.MediaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerImageTest {

    @TempDir Path tmp;

    private ChatHistory history;
    private KelsyRuntime runtime;

    @AfterEach
    void tearDown() throws Exception {
        if (history != null) {
            history.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void sendImageGoesToPeerNotSecretary() throws Exception {
        List<String> chats = new ArrayList<>();
        List<String> imagePlains = new ArrayList<>();
        ChatController.PeerSender peer = new ChatController.PeerSender() {
            @Override
            public void sendChat(String text) {
                chats.add(text);
            }

            @Override
            public void sendImagePayload(String metaPlaintext, List<String> chunkPlaintexts) {
                imagePlains.add(metaPlaintext);
                imagePlains.addAll(chunkPlaintexts);
            }
        };
        ChatController c = controller(peer, new ArrayList<>(), true, true);
        byte[] png = tinyPng();
        assertTrue(c.sendImage(new ImageDraft(png, "image/png", "@tars 见图")).accepted());
        assertTrue(chats.isEmpty());
        assertEquals(1, c.getMessages().stream().filter(m -> m.kind() == MessageKind.IMAGE).count());
        assertEquals(Sender.SELF, c.getMessages().getLast().sender());
        assertEquals("@tars 见图", c.getMessages().getLast().content());
        assertTrue(imagePlains.size() >= 2);
        ImageWire.Meta meta = ImageWire.parseMeta(imagePlains.get(0));
        assertEquals("@tars 见图", meta.caption());
        assertTrue(Files.isRegularFile(c.mediaFile(c.getMessages().getLast().previewRel())));
    }

    @Test
    void offlineRejectsImage() throws Exception {
        ChatController c = controller(new ChatController.PeerSender() {
            @Override
            public void sendChat(String text) {
            }
        }, new ArrayList<>(), false, false, true);
        assertTrue(!c.sendImage(new ImageDraft(tinyPng(), "image/png", "")).accepted());
    }

    private ChatController controller(
            ChatController.PeerSender peer,
            List<String> asked,
            boolean enabled,
            boolean configured) throws Exception {
        return controller(peer, asked, enabled, configured, false);
    }

    private ChatController controller(
            ChatController.PeerSender peer,
            List<String> asked,
            boolean enabled,
            boolean configured,
            boolean offline) throws Exception {
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
                peer,
                settings,
                runtime,
                offline,
                new MediaStore(tmp.resolve("media")));
    }

    private static byte[] tinyPng() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
