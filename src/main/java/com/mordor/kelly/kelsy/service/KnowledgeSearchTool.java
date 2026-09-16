package com.mordor.kelly.kelsy.service;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AgentScope 工具：按 FTS 检索知识卡片，供模型在预检索候选不够时补查。
 */
public final class KnowledgeSearchTool implements AgentTool {

    static final int MAX_RESULTS = 12;

    private final KnowledgeStore store;

    public KnowledgeSearchTool(KnowledgeStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return "knowledge_search";
    }

    @Override
    public String getDescription() {
        return "Search archived knowledge cards by keywords and optional date window.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description", "Search query, including optional date words such as 上周 or 2026-03");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", query);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String query = queryOf(param);
        List<KnowledgeStore.Hit> hits = store.search(FindQuery.parse(query, LocalDate.now()), true);
        int n = Math.min(MAX_RESULTS, hits.size());
        if (n == 0) {
            return Mono.just(ToolResultBlock.text("No matches."));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            KnowledgeStore.Hit hit = hits.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(hit.relativePath())
                    .append("  ")
                    .append(hit.snippet())
                    .append("  ")
                    .append(String.format(Locale.ROOT, "%.2f", hit.score()));
        }
        return Mono.just(ToolResultBlock.text(sb.toString()));
    }

    private static String queryOf(ToolCallParam param) {
        if (param == null) {
            return "";
        }
        Map<String, Object> input = param.getInput();
        if (input == null) {
            return "";
        }
        Object q = input.get("query");
        return q == null ? "" : String.valueOf(q);
    }
}
