package com.mordor.kelly.kelsy.service;

import java.time.LocalDate;
import java.util.List;

/**
 * 对话 ASK 预检索：抽词后查 FTS，分数够才把 top 候选附到模型输入。
 */
public record AskGrounding(boolean searched, List<KnowledgeStore.Hit> attached, String original) {

    public static final double DEFAULT_MIN_SCORE = 0.20;
    public static final double RECALL_MIN_SCORE = 0.05;
    public static final int MAX_ATTACH = 5;

    public AskGrounding {
        attached = attached == null ? List.of() : List.copyOf(attached);
    }

    public static AskGrounding prepare(KnowledgeStore store, String userText, LocalDate today) {
        if (LocalEvidence.terms(userText).isEmpty()) {
            return new AskGrounding(false, List.of(), userText);
        }
        if (store == null) return new AskGrounding(false, List.of(), userText);
        if (LocalEvidence.mentionsTodos(userText)) return new AskGrounding(false, List.of(),
            "【任务查询】请先使用 todo_list/get 获取实时状态；涉及文章事实时再 knowledge_search。\n" + userText);
        if(store.index()==null)return new AskGrounding(false,List.of(),"【知识检索暂不可用】可以继续处理一般请求；不要据此声称历史事实不存在。\n"+userText);
        try {
        double min = LocalEvidence.looksLikeRecall(userText) ? RECALL_MIN_SCORE : DEFAULT_MIN_SCORE;
        FindQuery parsed = FindQuery.parse(userText, today);
        String kind = LocalEvidence.mentionsMeetings(userText) ? "meeting" : LocalEvidence.mentionsDecisions(userText) ? "decision" : null;
        String searchText = kind == null ? userText : userText.replace("会议", " ").replace("纪要", " ").replace("决定", " ");
        List<String> terms = LocalEvidence.terms(searchText).stream()
                .filter(t -> kind == null || !(t.equals("会议") || t.equals("纪要") || t.equals("决定"))).toList();
        java.util.Map<String,KnowledgeStore.Hit> candidates = new java.util.LinkedHashMap<>();
        if(kind != null)for(var hit:store.index().search(new FindQuery(parsed.fromInclusive(),parsed.toInclusive(),terms),kind,java.util.Set.of(),MAX_ATTACH))
            candidates.put(hit.relativePath(),hit);
        // Preserve lexical recall for articles whose type is generic but whose text is relevant.
        for(var hit:store.search(new FindQuery(parsed.fromInclusive(),parsed.toInclusive(),LocalEvidence.terms(userText))))
            candidates.putIfAbsent(hit.relativePath(),hit);
        List<KnowledgeStore.Hit> attached = candidates.values().stream()
                .filter(hit -> !KnowledgeStore.isAskCandidateExcluded(hit.relativePath()))
                .filter(hit -> hit.score() >= min).limit(MAX_ATTACH).toList();
        String state = store.index() == null ? "FAILED" : store.index().status().state();
        String original = "STALE".equals(state) ? "【部分知识文件未索引】现有候选仍可使用；不要自动修复、删除文件或反复重试。\n" + userText : "READY".equals(state) ? userText : "【知识索引状态：" + state + "】空结果不代表没有归档，请稍后补查。\n" + userText;
        return new AskGrounding(true, attached, original);
        }catch(RuntimeException e){return new AskGrounding(false,List.of(),"【知识检索暂不可用】可以继续处理一般请求；涉及已归档事实需说明尚未核验，不要推测。\n"+userText);}
    }

    public String messageForModel() {
        if (attached.isEmpty()) {
            return original;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【已检索候选】请先 knowledge_get(path,expectedHash) 核验下列路径，结论以卡片字段为准。不要把 MEMORY.md 里没有当成没归档。\n");
        for (KnowledgeStore.Hit hit : attached) {
            if (KnowledgeStore.isAskCandidateExcluded(hit.relativePath())) {
                continue;
            }
            sb.append("- ").append(hit.relativePath()).append(" hash=").append(hit.contentHash()).append(" （").append(hit.snippet()).append("）\n");
        }
        sb.append("\n用户原话：\n").append(original);
        return sb.toString();
    }

    public List<String> citationPaths() {
        return attached.stream()
                .map(KnowledgeStore.Hit::relativePath)
                .filter(path -> !KnowledgeStore.isAskCandidateExcluded(path))
                .toList();
    }
}
