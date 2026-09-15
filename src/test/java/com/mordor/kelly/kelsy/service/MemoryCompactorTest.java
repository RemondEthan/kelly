package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCompactorTest {

    @Test
    void dropsOldPointerWithCardPath() {
        String fat = "# Memory\n\n" + "x".repeat(5000)
                + "\n- 2020-01-01 旧会 → knowledge/meetings/2020-01-01-旧.md\n"
                + "- 喜欢深色主题\n";
        var r = MemoryCompactor.compact(fat, LocalDate.of(2026, 9, 15));
        assertTrue(r.memoryMarkdown().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= MemoryCompactor.LIMIT_BYTES);
        assertTrue(r.memoryMarkdown().contains("深色主题"));
        assertFalse(r.memoryMarkdown().contains("2020-01-01-旧.md"));
        assertTrue(r.inboxCards().isEmpty());
    }

    @Test
    void promotesOrphanLineToInbox() {
        String fat = "# Memory\n\n" + "y".repeat(5000) + "\n- 2020-01-01 没有路径的流水\n";
        var r = MemoryCompactor.compact(fat, LocalDate.of(2026, 9, 15));
        assertEquals(1, r.inboxCards().size());
        assertTrue(r.inboxCards().get(0).relativePath().startsWith("knowledge/inbox/"));
        assertTrue(r.inboxCards().get(0).markdown().contains("没有路径的流水"));
    }

    @Test
    void underLimitUnchanged() {
        String small = "# Memory\n\n- 喜欢深色主题\n";
        var r = MemoryCompactor.compact(small, LocalDate.of(2026, 9, 15));
        assertEquals(small, r.memoryMarkdown());
        assertTrue(r.inboxCards().isEmpty());
        assertFalse(r.backup());
    }
}
