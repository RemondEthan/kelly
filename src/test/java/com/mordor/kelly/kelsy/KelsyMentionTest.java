package com.mordor.kelly.kelsy;

import com.mordor.kelly.model.RoomMember;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KelsyMentionTest {

    @Test
    void defaultTarsPrefix() {
        assertTrue(KelsyMention.isMention("@tars 你好", RoomMember.SECRETARY_NAME));
        assertTrue(KelsyMention.isMention("  @TARS 帮我记 ", "tars"));
        assertEquals("你好", KelsyMention.strip("@tars 你好", "tars"));
        assertEquals("帮我记", KelsyMention.strip("  @TARS 帮我记 ", "tars"));
        assertEquals("@tars ", KelsyMention.insert("tars"));
    }

    @Test
    void customNickOnly() {
        assertTrue(KelsyMention.isMention("@Ada 你好", "Ada"));
        assertTrue(KelsyMention.isMention("  @ada 帮我 ", "Ada"));
        assertEquals("你好", KelsyMention.strip("@Ada 你好", "Ada"));
        assertFalse(KelsyMention.isMention("@tars 你好", "Ada"));
        assertFalse(KelsyMention.isMention("@kelsy 你好", "Ada"));
        assertFalse(KelsyMention.isMention("请 @Ada 看看", "Ada"));
        assertFalse(KelsyMention.isMention("@Adafoo 嗨", "Ada"));
        assertEquals("@Ada ", KelsyMention.insert("Ada"));
    }

    @Test
    void kelsyNickIsMentionOnlyWhenChosen() {
        assertFalse(KelsyMention.isMention("@kelsy 你好", "tars"));
        assertTrue(KelsyMention.isMention("@kelsy 你好", "kelsy"));
        assertEquals("你好", KelsyMention.strip("@kelsy 你好", "kelsy"));
    }

    @Test
    void mentionAloneIsEmptyBody() {
        assertTrue(KelsyMention.isMention("@tars", "tars"));
        assertEquals("", KelsyMention.strip("@tars", "tars"));
        assertFalse(KelsyMention.isMention(null, "tars"));
        assertFalse(KelsyMention.isMention("hello", "tars"));
    }

    @Test
    void appliedLeadingSecretaryStillMentions() {
        var applied = com.mordor.kelly.ui.chat.MentionQuery.apply("@", 0, 1, "Ada");
        assertTrue(KelsyMention.isMention(applied.text(), "Ada"));
    }
}
