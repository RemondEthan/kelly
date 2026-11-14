package com.mordor.kelly.service;

import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.MessageKind;
import com.mordor.kelly.model.Sender;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCodecTest {

    @Test
    void encodeThenDecodePreservesFieldsIncludingTimestamp() {
        LocalDateTime at = LocalDateTime.of(2026, 8, 28, 15, 16, 32);
        Message original = new Message("id-1", Sender.PEER, "你好\n下一行", at, "张三");

        Message back = HistoryCodec.decode(HistoryCodec.encode(original));

        assertEquals(original, back);
        assertEquals(at, back.timestamp());
    }

    @Test
    void systemMessageRoundTripWithEmptyFrom() {
        Message original = new Message(
                "sys", Sender.SYSTEM, "已加入房间", LocalDateTime.of(2026, 1, 2, 3, 4, 5));

        assertEquals(original, HistoryCodec.decode(HistoryCodec.encode(original)));
    }

    @Test
    void rejectsIncompleteJson() {
        assertThrows(IllegalArgumentException.class, () -> HistoryCodec.decode("{\"id\":\"x\"}"));
    }

    @Test
    void imageMessageRoundTripDoesNotEmbedBytes() {
        LocalDateTime at = LocalDateTime.of(2026, 9, 14, 10, 0, 0);
        Message original = Message.image(
                "img-1", Sender.SELF, "见图", at, "me", "img-1", "img-1.jpg", "img-1.png");
        String json = HistoryCodec.encode(original);
        assertTrue(json.contains("\"kind\":\"IMAGE\""));
        assertTrue(!json.contains("iVBORw"));
        assertEquals(original, HistoryCodec.decode(json));
    }

    @Test
    void oldTextJsonDefaultsToTextKind() {
        Message back = HistoryCodec.decode(
                "{\"id\":\"id-1\",\"sender\":\"PEER\",\"from\":\"张三\","
                        + "\"timestamp\":\"2026-08-28T15:16:32\",\"content\":\"你好\"}");
        assertEquals(MessageKind.TEXT, back.kind());
        assertEquals("", back.mediaId());
    }

    @Test
    void assistantMessageRoundTrip() {
        LocalDateTime at = LocalDateTime.of(2026, 9, 2, 18, 0, 0);
        Message original = new Message("a1", Sender.ASSISTANT, "最终正文", at, "tars");
        assertEquals(original, HistoryCodec.decode(HistoryCodec.encode(original)));
    }
}
