package com.mordor.kelly.ui.chat;

import com.mordor.kelly.model.RoomMember;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionQueryTest {

    @Test
    void parseAtStartAndAfterSpace() {
        var start = MentionQuery.parse("@", 1).orElseThrow();
        assertEquals(0, start.atIndex());
        assertEquals("", start.query());

        var mid = MentionQuery.parse("hi @张", 5).orElseThrow();
        assertEquals(3, mid.atIndex());
        assertEquals("张", mid.query());
    }

    @Test
    void parseRejectsEmailAndFinishedMention() {
        assertTrue(MentionQuery.parse("a@b", 3).isEmpty());
        assertTrue(MentionQuery.parse("@张三 你好", 8).isEmpty());
        assertTrue(MentionQuery.parse("hello", 5).isEmpty());
    }

    @Test
    void parseQueryIsPrefixBeforeCaret() {
        var t = MentionQuery.parse("@张三", 2).orElseThrow();
        assertEquals(0, t.atIndex());
        assertEquals("张", t.query());
    }

    @Test
    void candidatesDropSelfSecretaryFirstPrefix() {
        var self = new RoomMember(RoomMember.SELF_ID, "我", true);
        var ada = new RoomMember(200001, "Ada", false);
        var bob = new RoomMember(200002, "Bob", false);
        var tars = RoomMember.kelsy("tars");
        var all = MentionQuery.candidates(List.of(self, ada, tars, bob), "");
        assertEquals(List.of("tars", "Ada", "Bob"),
                all.stream().map(RoomMember::username).toList());

        var filtered = MentionQuery.candidates(List.of(self, ada, tars, bob), "a");
        assertEquals(List.of("Ada"),
                filtered.stream().map(RoomMember::username).toList());
    }

    @Test
    void applyReplacesTokenAtCaret() {
        var applied = MentionQuery.apply("hello @张", 6, 8, "张三");
        assertEquals("hello @张三 ", applied.text());
        assertEquals("hello @张三 ".length(), applied.caret());
    }
}
