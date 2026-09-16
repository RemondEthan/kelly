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
        double min = LocalEvidence.looksLikeRecall(userText) ? RECALL_MIN_SCORE : DEFAULT_MIN_SCORE;
        List<KnowledgeStore.Hit> attached = store.search(FindQuery.parse(userText, today)).stream()
                .filter(hit -> !KnowledgeStore.isAskCandidateExcluded(hit.relativePath()))
                .filter(hit -> hit.score() >= min)
                .limit(MAX_ATTACH)
                .toList();
        return new AskGrounding(true, attached, userText);
    }

    public String messageForModel() {
        if (attached.isEmpty()) {
            return original;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【已检索候选】请先 read_file 下列路径，结论以卡片字段为准。不要把 MEMORY.md 里没有当成没归档。\n");
        for (KnowledgeStore.Hit hit : attached) {
            if (KnowledgeStore.isAskCandidateExcluded(hit.relativePath())) {
                continue;
            }
            sb.append("- ").append(hit.relativePath()).append(" （").append(hit.snippet()).append("）\n");
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
