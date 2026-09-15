package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskGroundingTest {

    @TempDir
    Path dir;

    @Test
    void attachesPersonCardForNamedAsk() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/people"));
        Files.writeString(dir.resolve("knowledge/people/张三.md"),
                """
                # 张三
                - 邮箱：zhangsan@example.com
                """);
        try (var store = new KnowledgeStore(dir)) {
            var grounding = AskGrounding.prepare(store, "张三邮箱是什么", LocalDate.of(2026, 9, 15));
            assertTrue(grounding.searched());
            assertTrue(grounding.attached().stream()
                    .anyMatch(h -> h.relativePath().equals("knowledge/people/张三.md")));
            String message = grounding.messageForModel();
            assertTrue(message.contains("【已检索候选】"));
            assertTrue(message.contains("张三邮箱是什么"));
            assertTrue(grounding.citationPaths().contains("knowledge/people/张三.md"));
        }
    }

    @Test
    void citationPathsSkipKnowledgeCatalog() {
        var grounding = new AskGrounding(true, List.of(
                new KnowledgeStore.Hit("knowledge/KNOWLEDGE.md", 1, "会议目录", 0.9),
                new KnowledgeStore.Hit("knowledge/meetings/2026-09-04-客户XX-交付licence.md", 1, "先申请再发货", 0.8)),
                "我最近有什么会议？");
        assertEquals(List.of("knowledge/meetings/2026-09-04-客户XX-交付licence.md"), grounding.citationPaths());
        assertFalse(grounding.messageForModel().contains("knowledge/KNOWLEDGE.md"));
    }

    @Test
    void greetingSkipsSearch() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/people"));
        Files.writeString(dir.resolve("knowledge/people/张三.md"), "# 张三\n");
        try (var store = new KnowledgeStore(dir)) {
            var grounding = AskGrounding.prepare(store, "你好", LocalDate.of(2026, 9, 15));
            assertFalse(grounding.searched());
            assertTrue(grounding.attached().isEmpty());
            assertEquals("你好", grounding.messageForModel());
        }
    }
}
