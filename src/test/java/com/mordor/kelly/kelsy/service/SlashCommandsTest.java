package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandsTest {

    @Test
    void noteRewrites() {
        var r = SlashCommands.parse("/note 今天和张三敲定了方案");
        assertTrue(r.send());
        assertTrue(r.outgoing().contains("memory_save"));
        assertTrue(r.outgoing().contains("今天和张三敲定了方案"));
        assertTrue(r.outgoing().contains("卡片"));
        assertTrue(r.outgoing().contains("澄清"));
        assertTrue(r.outgoing().contains("knowledge/meetings"));
    }

    @Test
    void noteIsCaseInsensitive() {
        var r = SlashCommands.parse("/NOTE hello");
        assertTrue(r.send());
        assertTrue(r.outgoing().contains("hello"));
    }

    @Test
    void noteWithoutBodyRejected() {
        var r = SlashCommands.parse("/note");
        assertFalse(r.send());
        assertEquals("请写上要记的内容", r.error());
    }

    @Test
    void todayIgnoresTrailingText() {
        var r = SlashCommands.parse("/today extra");
        assertTrue(r.send());
        assertTrue(r.outgoing().contains("knowledge_search"));
        assertFalse(r.outgoing().contains("memory_search"));
        assertFalse(r.outgoing().contains("extra"));
    }

    @Test
    void tidyRewrites() {
        var r = SlashCommands.parse("/tidy");
        assertTrue(r.send());
        assertTrue(r.outgoing().contains("MEMORY.md"));
        assertTrue(r.outgoing().contains("不要 write_file"));
        assertTrue(r.outgoing().contains("meetings"));
    }

    @Test
    void unknownSlashPassesThrough() {
        var r = SlashCommands.parse("/foo bar");
        assertTrue(r.send());
        assertEquals("/foo bar", r.outgoing());
    }

    @Test
    void plainTextPassesThrough() {
        var r = SlashCommands.parse("你好");
        assertTrue(r.send());
        assertEquals("你好", r.outgoing());
    }

    @Test
    void findIsLocal() {
        var r = SlashCommands.parse("/find 半年前 张三");
        assertFalse(r.send());
        assertTrue(r.find());
        assertEquals("半年前 张三", r.outgoing());
    }

    @Test
    void findEmptyRejected() {
        var r = SlashCommands.parse("/find");
        assertFalse(r.send());
        assertFalse(r.find());
        assertTrue(r.error().contains("/find"));
    }
}
