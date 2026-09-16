package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeStoreSearchTest {

    @TempDir
    Path dir;

    @Test
    void hitsDatedDiaryAndSkipsOutOfWindow() throws Exception {
        Files.createDirectories(dir.resolve("memory"));
        Files.createDirectories(dir.resolve("knowledge"));
        Files.writeString(dir.resolve("MEMORY.md"), "- 张三：长期合作\n");
        Files.writeString(dir.resolve("memory/2026-03-15.md"), "- 与张三敲定评审方案\n");
        Files.writeString(dir.resolve("memory/2026-08-01.md"), "- 与张三喝咖啡\n");
        var store = new KnowledgeStore(dir);
        var q = FindQuery.parse("半年前 张三", LocalDate.of(2026, 9, 2));
        var hits = store.search(q);
        assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("memory/2026-03-15.md")));
        assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("MEMORY.md")));
        assertTrue(hits.stream().noneMatch(h -> h.relativePath().equals("memory/2026-08-01.md")));
        assertTrue(hits.size() >= 2);
    }

    @Test
    void hitsMeetingCardByLicenceAlias() throws Exception {
        Files.createDirectories(dir.resolve("memory"));
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Files.writeString(dir.resolve("MEMORY.md"),
                "- 2026-09-04 客户XX 交付 licence 许可证 → knowledge/meetings/2026-09-04-客户XX-交付licence.md\n");
        Files.writeString(
                dir.resolve("knowledge/meetings/2026-09-04-客户XX-交付licence.md"),
                """
                # 会议 · 客户XX · 交付 licence
                - 结论：先申请再发货
                - 别名：licence, license, 许可证, 交付许可
                """);
        var store = new KnowledgeStore(dir);
        var byLicense = store.search(FindQuery.parse("许可证", LocalDate.of(2026, 9, 4)));
        var byLicence = store.search(FindQuery.parse("licence", LocalDate.of(2026, 9, 4)));
        assertTrue(byLicense.stream().anyMatch(h ->
                h.relativePath().equals("knowledge/meetings/2026-09-04-客户XX-交付licence.md")));
        assertTrue(byLicence.stream().anyMatch(h ->
                h.relativePath().equals("knowledge/meetings/2026-09-04-客户XX-交付licence.md")));
    }

    @Test
    void dateWindowKeepsInRangeHitWhenManyOutOfWindow() throws Exception {
        Files.createDirectories(dir.resolve("memory"));
        for (int i = 0; i < 80; i++) {
            var day = LocalDate.of(2026, 6, 1).plusDays(i);
            Files.writeString(dir.resolve("memory/" + day + ".md"),
                    "- 张三 张三 张三 张三 张三 高相关\n");
        }
        Files.writeString(dir.resolve("memory/2026-03-15.md"), "- 与张三敲定评审方案\n");
        var store = new KnowledgeStore(dir);
        var hits = store.search(FindQuery.parse("半年前 张三", LocalDate.of(2026, 9, 2)));
        assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("memory/2026-03-15.md")));
        assertTrue(hits.stream().noneMatch(h -> h.relativePath().equals("memory/2026-06-01.md")));
        assertTrue(hits.size() <= KnowledgeStore.MAX_HITS);
    }

    @Test
    void cardsContainingFindsPeoplePage() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/people"));
        Files.writeString(dir.resolve("knowledge/KNOWLEDGE.md"), "- knowledge/people/张三.md — 合作方\n");
        Files.writeString(dir.resolve("knowledge/people/张三.md"), "# 张三\n- 角色：合作方\n");
        var store = new KnowledgeStore(dir);
        assertEquals(
                List.of("knowledge/people/张三.md"),
                store.cardsContaining(List.of("张三")));
        assertTrue(store.cardPaths("knowledge/meetings").isEmpty());
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Files.writeString(dir.resolve("knowledge/meetings/2026-09-04-评审.md"), "# 会议\n");
        assertEquals(List.of("knowledge/meetings/2026-09-04-评审.md"),
                store.cardPaths("knowledge/meetings"));
    }

    @Test
    void cardsContainingSkipsMemoryFiles() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/people"));
        Files.createDirectories(dir.resolve("memory"));
        Files.writeString(dir.resolve("MEMORY.md"), "- 张三：长期合作\n");
        Files.writeString(dir.resolve("memory/2026-03-15.md"), "- 与张三敲定评审方案\n");
        Files.writeString(dir.resolve("knowledge/people/张三.md"), "# 张三\n- 角色：合作方\n");
        var store = new KnowledgeStore(dir);
        assertEquals(List.of("knowledge/people/张三.md"), store.cardsContaining(List.of("张三")));
    }

    @Test
    void cardsContainingAnyNeedleAgreesOnFtsAndFallback() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/people"));
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Files.writeString(dir.resolve("knowledge/KNOWLEDGE.md"), "- 张三 评审\n");
        Files.writeString(dir.resolve("knowledge/people/张三.md"), "# 张三\n- 角色：合作方\n");
        Files.writeString(dir.resolve("knowledge/meetings/评审.md"), "# 评审\n- 结论：通过\n");
        Files.writeString(dir.resolve("MEMORY.md"), "- 张三与评审\n");
        var terms = List.of("张三", "评审");
        var expected = Set.of("knowledge/people/张三.md", "knowledge/meetings/评审.md");
        var fts = new KnowledgeStore(dir).cardsContaining(terms);
        var fallback = new KnowledgeStore(dir, null).cardsContaining(terms);
        assertEquals(expected, Set.copyOf(fts));
        assertEquals(expected, Set.copyOf(fallback));
    }

    @Test
    void searchWithinTtlMissesUnupsertedFileThenHitsAfterUpsert() throws Exception {
        Files.writeString(dir.resolve("MEMORY.md"), "- 张三：长期合作\n");
        var today = LocalDate.of(2026, 9, 4);
        try (var store = new KnowledgeStore(dir)) {
            store.search(FindQuery.parse("张三", today));
            Files.createDirectories(dir.resolve("knowledge/people"));
            Files.writeString(dir.resolve("knowledge/people/李四.md"), "# 李四\n- 角色：合作方\n");
            var missed = store.search(FindQuery.parse("李四", today));
            assertTrue(missed.stream().noneMatch(h -> h.relativePath().equals("knowledge/people/李四.md")));
            store.upsert("knowledge/people/李四.md");
            var hits = store.search(FindQuery.parse("李四", today));
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("knowledge/people/李四.md")));
        }
    }

    @Test
    void unrecoverableIndexFallsBackToScan() throws Exception {
        Files.writeString(dir.resolve("MEMORY.md"), "- 张三：长期合作\n");
        Files.createDirectories(dir.resolve(".kelly-index.db/nested"));
        Files.writeString(dir.resolve(".kelly-index.db/nested/x"), "block");
        try (var store = new KnowledgeStore(dir)) {
            assertNull(store.index());
            var hits = store.search(FindQuery.parse("张三", LocalDate.of(2026, 9, 4)));
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("MEMORY.md")));
        }
    }
}
