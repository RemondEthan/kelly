package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeStoreUserRootTest {

    @TempDir
    Path dir;

    @Test
    void forUserReadsNamespacedMemory() throws Exception {
        Files.createDirectories(dir.resolve("remond/memory"));
        Files.writeString(dir.resolve("remond/MEMORY.md"), "- 用户索引");
        Files.writeString(dir.resolve("remond/memory/2026-09-02.md"), "- 今天下午讨论");
        Files.writeString(dir.resolve("MEMORY.md"), "- 根上种子");

        KnowledgeStore store = KnowledgeStore.forUser(dir, "remond");
        var memory = store.read("MEMORY.md");
        assertInstanceOf(KnowledgeStore.Read.Ok.class, memory);
        assertTrue(((KnowledgeStore.Read.Ok) memory).markdown().contains("用户索引"));
        assertTrue(store.list().stream().anyMatch(e ->
                e.children().stream().anyMatch(c -> "memory/2026-09-02.md".equals(c.relativePath()))));
    }

    @Test
    void knowledgeRootJoinsUsername() {
        assertEquals(dir.resolve("remond").toAbsolutePath().normalize(),
                KnowledgeStore.knowledgeRoot(dir, "remond"));
    }
}
