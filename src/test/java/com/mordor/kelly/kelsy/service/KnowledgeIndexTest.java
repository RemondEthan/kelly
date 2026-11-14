package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Tag;
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
        ArticleFixtures.writeString(dir.resolve("MEMORY.md"), "- ptr\n");
        ArticleFixtures.writeString(
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
        ArticleFixtures.writeString(dir.resolve("memory/2026-03-15.md"), "- 与张三敲定评审方案\n");
        ArticleFixtures.writeString(dir.resolve("memory/2026-08-01.md"), "- 与张三喝咖啡\n");
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("半年前 张三", LocalDate.of(2026, 9, 2)), 12);
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("memory/2026-03-15.md")));
            assertTrue(hits.stream().noneMatch(h -> h.relativePath().equals("memory/2026-08-01.md")));
        }
    }

    @Test
    void dateWindowKeepsInRangeHitPastSqlLimit() throws Exception {
        Files.createDirectories(dir.resolve("memory"));
        for (int i = 0; i < 80; i++) {
            var day = LocalDate.of(2026, 6, 1).plusDays(i);
            ArticleFixtures.writeString(dir.resolve("memory/" + day + ".md"),
                    "- 张三 张三 张三 张三 张三 高相关\n");
        }
        ArticleFixtures.writeString(dir.resolve("memory/2026-03-15.md"), "- 与张三敲定评审方案\n");
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("半年前 张三", LocalDate.of(2026, 9, 2)), 12);
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("memory/2026-03-15.md")));
            assertTrue(hits.stream().noneMatch(h -> h.relativePath().equals("memory/2026-06-01.md")));
            assertTrue(hits.size() <= 12);
        }
    }

    @Test
    void corruptDbDeletedAndReopened() throws Exception {
        ArticleFixtures.writeString(dir.resolve("MEMORY.md"), "- a\n");
        ArticleFixtures.writeString(dir.resolve(".kelly-index.db"), "not a sqlite database");
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("a", LocalDate.of(2026, 1, 1)), 12);
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("MEMORY.md")));
        }
    }

    @Test
    void schemaBumpRebuilds() throws Exception {
        ArticleFixtures.writeString(dir.resolve("MEMORY.md"), "- a\n");
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
        }
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(".kelly-index.db"))) {
            c.createStatement().executeUpdate("DROP TRIGGER search_content_ai");
        }
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("a", LocalDate.of(2026, 1, 1)), 12);
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("MEMORY.md")));
        }
    }

    @Tag("slow")
    @Test
    void reconcileTenThousandCardsFindsLastFile() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/inbox"));
        for (int i = 0; i < 10_000; i++) {
            ArticleFixtures.writeString(dir.resolve("knowledge/inbox/n" + i + ".md"), "- token" + i + "\n");
        }
        ArticleFixtures.writeString(dir.resolve("MEMORY.md"), "- ptr\n");
        long start = System.nanoTime();
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("token9999", LocalDate.of(2026, 9, 4)), 5);
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("knowledge/inbox/n9999.md")));
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        System.out.println("knowledge reconcile 10000 cards: " + ms + "ms");
    }
}
