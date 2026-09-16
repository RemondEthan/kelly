package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.common.Diagnostics;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 包装 AgentScope 写工具：落盘成功后立刻 {@link KnowledgeStore#upsert}，不必等 TTL reconcile。
 */
final class IndexingAgentTool implements AgentTool {

    private static final List<String> WRITE_TOOLS = List.of("write_file", "edit_file", "memory_save");

    private final AgentTool inner;
    private final KnowledgeStore store;

    IndexingAgentTool(AgentTool inner, KnowledgeStore store) {
        this.inner = inner;
        this.store = store;
    }

    static void install(Toolkit toolkit, KnowledgeStore store) {
        if (toolkit == null || store == null) {
            return;
        }
        for (String name : WRITE_TOOLS) {
            AgentTool inner = toolkit.getTool(name);
            if (inner == null || inner instanceof IndexingAgentTool) {
                continue;
            }
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new IndexingAgentTool(inner, store));
        }
    }

    @Override
    public String getName() {
        return inner.getName();
    }

    @Override
    public String getDescription() {
        return inner.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return inner.getParameters();
    }

    @Override
    public Boolean getStrict() {
        return inner.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return inner.getOutputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return inner.isReadOnly();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return inner.callAsync(param).doOnSuccess(ignored -> indexAfter(param));
    }

    private void indexAfter(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        for (String path : pathsToIndex(inner.getName(), input)) {
            try {
                store.upsert(path);
            } catch (RuntimeException e) {
                Diagnostics.warn("kelsy", "index upsert %s failed: %s", path, e.toString());
            }
        }
    }

    static List<String> pathsToIndex(String toolName, Map<String, Object> input) {
        if ("memory_save".equals(toolName)) {
            return List.of("MEMORY.md", "memory/" + LocalDate.now() + ".md");
        }
        if ("write_file".equals(toolName) || "edit_file".equals(toolName)) {
            String path = pathOf(input);
            return isIndexable(path) ? List.of(normalize(path)) : List.of();
        }
        return List.of();
    }

    private static String pathOf(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        for (String key : List.of("path", "file_path", "file")) {
            Object v = input.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static boolean isIndexable(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String n = normalize(path);
        if ("MEMORY.md".equals(n)) {
            return true;
        }
        String lower = n.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md")
                && (n.startsWith("memory/") || n.startsWith("knowledge/"));
    }

    private static String normalize(String path) {
        String n = path.replace('\\', '/').strip();
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        return n;
    }
}
