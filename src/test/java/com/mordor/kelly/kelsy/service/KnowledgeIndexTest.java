package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeIndexTest {

    @TempDir Path dir;

    @Test
    void findsAliasAfterReconcile() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Files.writeString(dir.resolve("MEMORY.md"), "- ptr\n");
        Files.writeString(
                dir.resolve("knowledge/meetings/2026-09-04-客户XX-交付licence.md"),
                """
                # 会议 · 客户XX · 交付 licence
                - 别名：licence, license, 许可证, 交付许可
                - 结论：先申请再发货
                """);
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("许可证", LocalDate.of(2026, 9, 4)), 12);
            assertTrue(hits.stream().anyMatch(h ->
                    h.relativePath().equals("knowledge/meetings/2026-09-04-客户XX-交付licence.md")));
        }
    }

    @Test
    void dateWindowSkipsOutOfRangeDiary() throws Exception {
        Files.createDirectories(dir.resolve("memory"));
        Files.writeString(dir.resolve("memory/2026-03-15.md"), "- 与张三敲定评审方案\n");
        Files.writeString(dir.resolve("memory/2026-08-01.md"), "- 与张三喝咖啡\n");
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("半年前 张三", LocalDate.of(2026, 9, 2)), 12);
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("memory/2026-03-15.md")));
            assertTrue(hits.stream().noneMatch(h -> h.relativePath().equals("memory/2026-08-01.md")));
        }
    }

    @Test
    void schemaBumpRebuilds() throws Exception {
        Files.writeString(dir.resolve("MEMORY.md"), "- a\n");
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
        }
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(".kelly-index.db"))) {
            c.createStatement().executeUpdate("UPDATE meta SET value='0' WHERE key='schema_version'");
        }
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("a", LocalDate.of(2026, 1, 1)), 12);
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("MEMORY.md")));
        }
    }
}
