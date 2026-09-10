package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgePathExtractorTest {

    @Test
    void findsMemory() {
        assertEquals("MEMORY.md", KnowledgePathExtractor.first("wrote MEMORY.md ok").orElseThrow());
    }

    @Test
    void findsDaily() {
        assertEquals("memory/2026-09-02.md",
                KnowledgePathExtractor.first("saved memory/2026-09-02.md").orElseThrow());
    }

    @Test
    void findsKnowledge() {
        assertEquals("knowledge/people/a.md",
                KnowledgePathExtractor.first("path=knowledge/people/a.md").orElseThrow());
    }

    @Test
    void findsChineseName() {
        assertEquals("knowledge/people/张三.md",
                KnowledgePathExtractor.first("见 knowledge/people/张三.md").orElseThrow());
    }

    @Test
    void emptyWhenNone() {
        assertTrue(KnowledgePathExtractor.first("no files here").isEmpty());
    }

    @Test
    void allKeepsOrderAndDedups() {
        var paths = KnowledgePathExtractor.all(
                "见 knowledge/meetings/a.md 和 memory/2026-09-04.md 以及 knowledge/meetings/a.md");
        assertEquals(
                List.of("knowledge/meetings/a.md", "memory/2026-09-04.md"),
                paths);
    }

    @Test
    void allEmptyWhenNone() {
        assertTrue(KnowledgePathExtractor.all("no files").isEmpty());
        assertTrue(KnowledgePathExtractor.all(null).isEmpty());
    }
}
