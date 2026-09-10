package com.mordor.kelly.kelsy.service;

import java.time.LocalDate;
import java.util.List;

public final class LocalEvidence {

    private static final List<String> STOPS = List.of(
            "最近有什么", "当时怎么定的", "怎么定的", "是谁", "请问", "一下",
            "我最近", "有什么", "当时", "最近", "我");

    private LocalEvidence() {
    }

    public static boolean mentionsTodos(String text) {
        return text != null && text.contains("待办");
    }

    public static boolean mentionsMeetings(String text) {
        return text != null && (text.contains("会议") || text.contains("纪要"));
    }

    public static boolean mentionsDecisions(String text) {
        return text != null && text.contains("决定");
    }

    public static List<String> terms(String outgoing) {
        if (outgoing == null || outgoing.isBlank()) {
            return List.of();
        }
        String q = outgoing;
        for (String mark : List.of("？", "?", "！", "!", "。", "，", ",")) {
            q = q.replace(mark, " ");
        }
        for (String stop : STOPS) {
            q = q.replace(stop, " ");
        }
        return FindQuery.parse(q, LocalDate.now()).keywords().stream()
                .filter(k -> k.length() >= 2)
                .toList();
    }
}
