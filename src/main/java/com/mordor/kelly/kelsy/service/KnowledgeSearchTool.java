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
        properties.put("kind", Map.of("type","string","enum",List.of("article","meeting","decision","person","project","playbook","inbox","memory","daily")));
        properties.put("tags", Map.of("type","array","items",Map.of("type","string"),"maxItems",32));
        properties.put("from", Map.of("type","string","description","Inclusive ISO date"));
        properties.put("before", Map.of("type","string","description","Exclusive ISO date"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            try {
                String query = queryOf(param);
                Map<String,Object> input = param == null || param.getInput() == null ? Map.of() : param.getInput();
                FindQuery parsed = FindQuery.parse(query, LocalDate.now());
                LocalDate from = input.get("from") == null ? parsed.fromInclusive() : LocalDate.parse(input.get("from").toString());
                LocalDate to = input.get("before") == null ? parsed.toInclusive() : LocalDate.parse(input.get("before").toString()).minusDays(1);
                if(from==null&&to!=null)from=LocalDate.of(1,1,1);
                if(from!=null&&to==null)to=LocalDate.of(9999,12,31);
                if(from != null && from.isAfter(to)) throw new IllegalArgumentException("Provide a valid from/before date window");
                String kind = input.get("kind") == null ? null : input.get("kind").toString();
                if(kind != null && !List.of("article","meeting","decision","person","project","playbook","inbox","memory","daily").contains(kind)) throw new IllegalArgumentException("Invalid kind");
                java.util.Set<String> tags = new java.util.LinkedHashSet<>();
                if(input.get("tags") != null){
                    if(!(input.get("tags") instanceof List<?> values) || values.size()>32)throw new IllegalArgumentException("Invalid tags");
                    for(Object tag:values){if(!(tag instanceof String text)||text.isBlank()||text.length()>100)throw new IllegalArgumentException("Invalid tag");tags.add(text);}
                }
                if(query.length()>400)throw new IllegalArgumentException("Query too long");
                if(searchTermsEmpty(query,kind,tags,from))throw new IllegalArgumentException("Provide keywords or filters");
                List<KnowledgeStore.Hit> hits = store.index().search(new FindQuery(from,to,parsed.keywords()),kind,tags,MAX_RESULTS);
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
                return ToolResultBlock.text(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("ok",false,"error",Map.of("code",e instanceof IllegalArgumentException ? "INVALID_ARGUMENT" : "INDEX_UNAVAILABLE","message",java.util.Objects.toString(e.getMessage(),"Search unavailable"),"retryable",true))));
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private static boolean searchTermsEmpty(String query,String kind,java.util.Set<String> tags,LocalDate from){
        return query.isBlank() && kind==null && tags.isEmpty() && from==null;
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
