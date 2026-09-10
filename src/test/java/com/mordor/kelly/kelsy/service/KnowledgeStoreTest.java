package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeStoreTest {

    @TempDir
    Path dir;

    KnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        WorkspaceSeeder.seed(dir);
        Files.createDirectories(dir.resolve("memory"));
        Files.writeString(dir.resolve("memory/2026-09-02.md"), "- hello");
        Files.createDirectories(dir.resolve("agents/kelsy/sessions"));
        Files.writeString(dir.resolve("agents/kelsy/sessions/main.jsonl"), "secret");
        store = new KnowledgeStore(dir);
    }

    @Test
    void listsFourRootsOnly() {
        var roots = store.list();
        var labels = roots.stream().map(KnowledgeStore.Entry::label).toList();
        assertEquals(List.of("规范", "索引", "日记", "知识"), labels);
        assertTrue(roots.stream().noneMatch(e -> e.relativePath().contains("agents")));
        assertTrue(roots.stream().anyMatch(e ->
                e.children().stream().anyMatch(c -> "memory/2026-09-02.md".equals(c.relativePath()))));
    }

    @Test
    void readOk() {
        var r = store.read("MEMORY.md");
        assertInstanceOf(KnowledgeStore.Read.Ok.class, r);
    }

    @Test
    void rejectEscape() {
        var r = store.read("../outside.md");
        assertInstanceOf(KnowledgeStore.Read.Rejected.class, r);
    }

    @Test
    void rejectAbsolute() {
        var r = store.read(dir.resolve("AGENTS.md").toAbsolutePath().toString());
        assertInstanceOf(KnowledgeStore.Read.Rejected.class, r);
    }

    @Test
    void tooLarge() throws Exception {
        Files.write(dir.resolve("MEMORY.md"), new byte[256 * 1024 + 1]);
        var r = store.read("MEMORY.md");
        assertInstanceOf(KnowledgeStore.Read.TooLarge.class, r);
    }

    @Test
    void missing() {
        var r = store.read("nope.md");
        assertInstanceOf(KnowledgeStore.Read.Missing.class, r);
    }

    @Test
    void memorySize() {
        assertTrue(store.memoryBytes() >= 0);
    }
}
