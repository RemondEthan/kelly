package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.kelsy.KelsyRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeUpgradeTest {

    @TempDir Path dir;

    @Test
    void oldLayoutIndexedWithoutRewritingCards() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Path card = dir.resolve("knowledge/meetings/2026-09-04-客户XX-交付licence.md");
        Files.writeString(card, "- 别名：许可证\n- 结论：先申请再发货\n");
        long mtime = Files.getLastModifiedTime(card).toMillis();
        Files.writeString(dir.resolve("MEMORY.md"), "- 2026-09-04 licence → knowledge/meetings/2026-09-04-客户XX-交付licence.md\n");
        KelsyRuntime.upgradeKnowledge(dir);
        try (var store = open(dir)) {
            var paths = store.search(FindQuery.parse("许可证", LocalDate.of(2026, 9, 4))).stream()
                    .map(KnowledgeStore.Hit::relativePath).collect(Collectors.toSet());
            assertTrue(paths.contains("knowledge/meetings/2026-09-04-客户XX-交付licence.md"));
            assertEquals(mtime, Files.getLastModifiedTime(card).toMillis());
            assertTrue(Files.isRegularFile(dir.resolve(".kelly-index.db")));
        }
    }

    @Test
    void upgradeCompactsOverLimitMemory() throws Exception {
        StringBuilder sb = new StringBuilder("# Memory\n\n- 喜欢深色主题\n");
        for (int i = 0; i < 80; i++) {
            sb.append("- 2010-01-")
                    .append(String.format("%02d", (i % 28) + 1))
                    .append(" 旧会 → knowledge/meetings/2010-01-old-")
                    .append(i)
                    .append(".md\n");
        }
        Files.writeString(dir.resolve("MEMORY.md"), sb.toString());
        assertTrue(Files.size(dir.resolve("MEMORY.md")) > MemoryCompactor.LIMIT_BYTES);
        KelsyRuntime.upgradeKnowledge(dir);
        assertTrue(Files.isRegularFile(dir.resolve("MEMORY.md.bak")));
        assertTrue(Files.size(dir.resolve("MEMORY.md")) <= MemoryCompactor.LIMIT_BYTES);
        String compact = Files.readString(dir.resolve("MEMORY.md"));
        assertTrue(compact.contains("深色主题"));
        assertFalse(compact.contains("2010-01-old-"));
    }

    @Test
    void deletedIndexRebuildsSamePaths() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Files.writeString(
                dir.resolve("knowledge/meetings/2026-09-04-客户XX-交付licence.md"),
                "- 别名：许可证\n- 结论：先申请再发货\n");
        Files.writeString(dir.resolve("MEMORY.md"),
                "- 2026-09-04 licence → knowledge/meetings/2026-09-04-客户XX-交付licence.md\n");
        KelsyRuntime.upgradeKnowledge(dir);
        Set<String> before;
        try (var store = open(dir)) {
            before = pathsOf(store, "许可证");
        }
        Files.deleteIfExists(dir.resolve(".kelly-index.db"));
        Files.deleteIfExists(dir.resolve(".kelly-index.db-wal"));
        Files.deleteIfExists(dir.resolve(".kelly-index.db-shm"));
        KelsyRuntime.upgradeKnowledge(dir);
        try (var store = open(dir)) {
            assertEquals(before, pathsOf(store, "许可证"));
            assertTrue(before.contains("knowledge/meetings/2026-09-04-客户XX-交付licence.md"));
            assertTrue(Files.isRegularFile(dir.resolve(".kelly-index.db")));
        }
    }

    private static Set<String> pathsOf(KnowledgeStore store, String query) {
        return store.search(FindQuery.parse(query, LocalDate.of(2026, 9, 4))).stream()
                .map(KnowledgeStore.Hit::relativePath)
                .collect(Collectors.toSet());
    }

    private static KnowledgeStore open(Path dir) {
        return new KnowledgeStore(dir);
    }
}
