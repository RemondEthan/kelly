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

import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingAgentToolTest {

    @TempDir
    Path dir;

    @Test
    void writeFileUpsertsWithoutWaitingForTtl() throws Exception {
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
            assertTrue(hits.stream().anyMatch(h ->
                    h.relativePath().equals("knowledge/people/李四.md")));
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
