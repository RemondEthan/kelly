package com.mordor.kelly.kelsy.service;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchToolTest {

    @TempDir
    Path dir;

    @Test
    void nameIsStable() {
        assertEquals("knowledge_search", new KnowledgeSearchTool(new KnowledgeStore(dir)).getName());
    }

    @Test
    void parametersDescribeQuery() {
        Map<String, Object> schema = new KnowledgeSearchTool(new KnowledgeStore(dir)).getParameters();
        assertEquals("object", schema.get("type"));
        assertEquals(List.of("query"), schema.get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) properties.get("query");
        assertEquals("string", query.get("type"));
    }

    @Test
    void callAsyncSeesFileWrittenAfterWarmSearch() throws Exception {
        Files.writeString(dir.resolve("MEMORY.md"), "- 张三：长期合作\n");
        try (var store = new KnowledgeStore(dir)) {
            store.search(FindQuery.parse("张三", java.time.LocalDate.of(2026, 9, 4)));
            Files.createDirectories(dir.resolve("knowledge/people"));
            Files.writeString(dir.resolve("knowledge/people/李四.md"), "# 李四\n");
            var tool = new KnowledgeSearchTool(store);
            ToolCallParam param = ToolCallParam.builder()
                    .input(Map.of("query", "李四"))
                    .build();
            ToolResultBlock result = tool.callAsync(param).block();
            String text = ((TextBlock) result.getOutput().getFirst()).getText();
            assertTrue(text.contains("knowledge/people/李四.md"));
        }
    }

    @Test
    void callAsyncFormatsTopHits() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/people"));
        Files.writeString(dir.resolve("knowledge/people/张三.md"),
                """
                # 张三
                - 邮箱：zhangsan@example.com
                """);
        try (var store = new KnowledgeStore(dir)) {
            var tool = new KnowledgeSearchTool(store);
            ToolCallParam param = ToolCallParam.builder()
                    .input(Map.of("query", "张三"))
                    .build();
            ToolResultBlock result = tool.callAsync(param).block();
            String text = ((TextBlock) result.getOutput().getFirst()).getText();
            assertTrue(text.contains("knowledge/people/张三.md"));
            assertTrue(text.contains("张三") || text.contains("邮箱"));
        }
    }
}
