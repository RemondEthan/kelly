package com.mordor.kelly.kelsy.service;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexingAgentToolTest {

    @TempDir
    Path dir;

    @Test
    void managedArticleWriteRequiresKnowledgeTool() throws Exception {
        Files.writeString(dir.resolve("MEMORY.md"), "- 张三\n");
        var today = LocalDate.of(2026, 9, 4);
        try (var store = new KnowledgeStore(dir)) {
            store.search(FindQuery.parse("张三", today));
            Path card = dir.resolve("knowledge/people/李四.md");
            Files.createDirectories(card.getParent());
            AgentTool inner = stub("write_file", param -> {
                try {
                    Files.writeString(card, "# 李四\n- 角色：合作方\n");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return ToolResultBlock.text("Wrote " + card);
            });
            new IndexingAgentTool(inner, store)
                    .callAsync(ToolCallParam.builder()
                            .input(Map.of("path", "knowledge/people/李四.md"))
                            .build())
                    .block();
            var hits = store.search(FindQuery.parse("李四", today));
            assertTrue(Files.notExists(card));
            assertTrue(hits.isEmpty());
        }
    }

    @Test
    void memorySaveUpsertsMemoryAndDiary() throws Exception {
        Files.writeString(dir.resolve("MEMORY.md"), "- 张三\n");
        var today = LocalDate.now();
        try (var store = new KnowledgeStore(dir)) {
            store.search(FindQuery.parse("张三", today));
            String diary = "memory/" + today + ".md";
            Files.createDirectories(dir.resolve("memory"));
            AgentTool inner = stub("memory_save", param -> {
                try {
                    Files.writeString(dir.resolve("MEMORY.md"), "- 张三\n- 喜欢深色主题\n");
                    Files.writeString(dir.resolve(diary), "- 喜欢深色主题\n");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return ToolResultBlock.text("Saved 1 memory");
            });
            new IndexingAgentTool(inner, store)
                    .callAsync(ToolCallParam.builder()
                            .input(Map.of("content", "- 喜欢深色主题"))
                            .build())
                    .block();
            var hits = store.search(FindQuery.parse("深色主题", today));
            assertTrue(hits.stream().anyMatch(h -> "MEMORY.md".equals(h.relativePath())));
            assertTrue(hits.stream().anyMatch(h -> diary.equals(h.relativePath())));
        }
    }

    @Test
    void fileToolsCannotReachInternalStorageThroughAnyPathAlias() throws Exception {
        try (var store = new KnowledgeStore(dir)) {
            byte[] database = Files.readAllBytes(dir.resolve(".kelly-data.db"));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            for (String tool : java.util.List.of("write_file", "edit_file")) {
                var guarded = new IndexingAgentTool(stub(tool, param -> {
                    calls.incrementAndGet();
                    return ToolResultBlock.text("Delegated");
                }), store);
                for (String key : java.util.List.of("path", "file_path", "file")) {
                    for (String path : java.util.List.of(
                            ".kelly-data.db", ".kelly-data.db-wal", ".kelly-data.db-shm",
                            ".kelly-data.db-journal", ".kelly-index.db", ".kelly-index.db-wal",
                            "./.kelly-data.db", ".//.kelly-data.db", ".KELLY-DATA.DB",
                            "sub/.kelly-data.db", ".kelly-data.db.damaged-backup")) {
                        var result = guarded.callAsync(ToolCallParam.builder()
                                .input(Map.of(key, path)).build()).block();
                        String text = ((io.agentscope.core.message.TextBlock) result.getOutput().getFirst()).getText();
                        assertTrue(text.contains("PATH_REJECTED"), tool + " " + key + " " + path);
                        assertEquals(0, calls.get(), "Rejected writes must not reach the underlying tool");
                    }
                }
                guarded.callAsync(ToolCallParam.builder()
                        .input(Map.of("path", "notes.txt", "file_path", ".kelly-data.db")).build()).block();
                assertEquals(0, calls.get());
            }
            assertArrayEquals(database, Files.readAllBytes(dir.resolve(".kelly-data.db")));
            assertTrue(store.todos().list(com.mordor.kelly.kelsy.todo.TodoRepository.Query.open()).items().isEmpty());
        }
    }

    @Test
    void ordinaryFilesStillReachTheUnderlyingTool() {
        try (var store = new KnowledgeStore(dir)) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            for (String tool : java.util.List.of("write_file", "edit_file")) {
                new IndexingAgentTool(stub(tool, param -> {
                    calls.incrementAndGet();
                    return ToolResultBlock.text("Delegated");
                }), store).callAsync(ToolCallParam.builder().input(Map.of("path", "notes.txt")).build()).block();
            }
            assertEquals(2, calls.get());
        }
    }

    private static AgentTool stub(String name, java.util.function.Function<ToolCallParam, ToolResultBlock> fn) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of();
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    return Mono.just(fn.apply(param));
                } catch (Exception e) {
                    return Mono.error(e);
                }
            }
        };
    }
}
