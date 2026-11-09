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
        return Mono.fromCallable(() -> {
            try {
                String query = queryOf(param);
                List<KnowledgeStore.Hit> hits = store.search(FindQuery.parse(query, LocalDate.now()));
                var status = store.index().status();
                var data = new LinkedHashMap<String,Object>();
                data.put("hits", hits.stream().limit(MAX_RESULTS).map(h -> Map.of(
                    "path",h.relativePath(),"uid",h.documentUid(),"citation",h.relativePath().startsWith("knowledge/")?"article:"+h.documentUid():h.relativePath(),"hash",h.contentHash(),
                    "line",h.line(),"snippet",h.snippet(),"rank",h.rank())).toList());
                data.put("indexState",status.state());
                // File-level maintenance diagnostics must not drive destructive agent repairs.
                data.put("unindexedFileCount",status.errors().keySet().stream().filter(p -> !p.equals("<scan>")).count());
                if(!status.errors().isEmpty())data.put("maintenanceNote",
                    "部分文件未索引，其他检索结果仍可用。格式问题需在维护流程中处理，不要自动修复、删除文件或反复重试检索。");
                data.put("lastReconciledAt",status.lastReconciledAt()==null?null:status.lastReconciledAt().toString());
                data.put("truncated",store.index().truncated());
                return ToolResultBlock.text(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("ok",true,"data",data)));
            } catch (Exception e) {
                return ToolResultBlock.text(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("ok",false,"error",Map.of("code","INDEX_UNAVAILABLE","message",java.util.Objects.toString(e.getMessage(),"Search unavailable"),"retryable",true))));
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
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
