package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void dropsOldestPointersUntilLimit() {
        StringBuilder sb = new StringBuilder("# Memory\n\n- 喜欢深色主题\n");
        LocalDate today = LocalDate.of(2026, 9, 15);
        for (int i = 0; i < 60; i++) {
            LocalDate day = today.minusDays(i % 10);
            sb.append("- ").append(day).append(" 近会")
                    .append("x".repeat(80))
                    .append(" → knowledge/meetings/").append(day).append("-n").append(i).append(".md\n");
        }
        var r = MemoryCompactor.compact(sb.toString(), today);
        assertTrue(r.memoryMarkdown().getBytes(StandardCharsets.UTF_8).length
                <= MemoryCompactor.LIMIT_BYTES);
        assertTrue(r.memoryMarkdown().contains("深色主题"));
        assertFalse(r.memoryMarkdown().contains("2026-09-06-n"));
    }

    @Test
    void leavesEvergreenWhenStillOverLimit() {
        String fat = "# Memory\n\n- 喜欢深色主题 " + "x".repeat(5000) + "\n";
        var r = MemoryCompactor.compact(fat, LocalDate.of(2026, 9, 15));
        assertTrue(r.memoryMarkdown().contains("深色主题"));
        assertTrue(r.memoryMarkdown().getBytes(StandardCharsets.UTF_8).length
                > MemoryCompactor.LIMIT_BYTES);
    }

    @Test
    void mergesExcessInboxCardsIntoOneBatch() {
        StringBuilder fat = new StringBuilder("# Memory\n\n- 喜欢深色主题\n");
        fat.append("x".repeat(5000)).append('\n');
        for (int i = 1; i <= 12; i++) {
            fat.append("- 2020-01-")
                    .append(String.format("%02d", i))
                    .append(" 孤儿流水")
                    .append(i)
                    .append('\n');
        }
        var r = MemoryCompactor.compact(fat.toString(), LocalDate.of(2026, 9, 15));
        assertEquals(1, r.inboxCards().size());
        assertEquals("knowledge/inbox/2026-09-15-batch.md", r.inboxCards().get(0).relativePath());
        String markdown = r.inboxCards().get(0).markdown();
        assertTrue(markdown.contains("孤儿流水1"));
        assertTrue(markdown.contains("孤儿流水12"));
    }

    @Test
    void applyWritesInboxBackupAndMemory(@TempDir Path dir) throws Exception {
        String original = "# Memory\n\n" + "y".repeat(5000) + "\n- 2020-01-01 没有路径的流水\n";
        Files.writeString(dir.resolve("MEMORY.md"), original);
        var r = MemoryCompactor.compact(original, LocalDate.of(2026, 9, 15));
        MemoryCompactor.apply(dir, r);
        assertTrue(Files.isRegularFile(dir.resolve("MEMORY.md.bak")));
        assertEquals(original, Files.readString(dir.resolve("MEMORY.md.bak")));
        assertEquals(r.memoryMarkdown(), Files.readString(dir.resolve("MEMORY.md")));
        assertFalse(Files.exists(dir.resolve("MEMORY.md.tmp")));
        assertEquals(1, r.inboxCards().size());
        Path inbox = dir.resolve(r.inboxCards().get(0).relativePath());
        assertTrue(Files.isRegularFile(inbox));
        assertTrue(Files.readString(inbox).contains("没有路径的流水"));
    }
}
