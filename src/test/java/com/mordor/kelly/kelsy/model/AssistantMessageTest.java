package com.mordor.kelly.kelsy.model;

import com.mordor.kelly.model.Sender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantMessageTest {

    @Test
    void thinkingStaysOutOfVisibleContent() {
        AssistantMessage msg = AssistantMessage.streaming(Sender.ASSISTANT);
        msg.appendThinking("先查日记");
        msg.append("今天下午有讨论");

        assertEquals(2, msg.blocks().size());
        assertEquals(MessageBlock.Kind.THINKING, msg.blocks().get(0).kind());
        assertEquals("先查日记", msg.blocks().get(0).content());
        assertEquals(MessageBlock.Kind.TEXT, msg.blocks().get(1).kind());
        assertEquals("今天下午有讨论", msg.content());
    }

    @Test
    void finishThinkingDoesNotFinishText() {
        AssistantMessage msg = AssistantMessage.streaming(Sender.ASSISTANT);
        msg.appendThinking("推理中");
        msg.append("正文");

        msg.finishThinking();

        assertFalse(msg.blocks().get(0).streamingProperty().get());
        assertTrue(msg.blocks().get(1).streamingProperty().get());
        assertTrue(msg.streamingProperty().get());
    }
}
