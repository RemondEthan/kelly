package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        try (var store = open(dir)) {
            store.index().reconcile();
            var paths = store.search(FindQuery.parse("许可证", LocalDate.of(2026, 9, 4))).stream()
                    .map(KnowledgeStore.Hit::relativePath).collect(Collectors.toSet());
            assertTrue(paths.contains("knowledge/meetings/2026-09-04-客户XX-交付licence.md"));
            assertEquals(mtime, Files.getLastModifiedTime(card).toMillis());
            assertTrue(Files.isRegularFile(dir.resolve(".kelly-index.db")));
        }
    }

    private static KnowledgeStore open(Path dir) {
        return new KnowledgeStore(dir);
    }
}
