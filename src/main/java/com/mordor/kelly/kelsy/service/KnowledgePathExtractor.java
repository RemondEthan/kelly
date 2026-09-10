package com.mordor.kelly.kelsy.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KnowledgePathExtractor {

    private static final Pattern PATH = Pattern.compile(
            "(?:MEMORY\\.md|AGENTS\\.md|memory/[\\p{L}\\p{N}._/-]+\\.md|knowledge/[\\p{L}\\p{N}._/-]+\\.md)");

    private KnowledgePathExtractor() {
    }

    public static Optional<String> first(String text) {
        return all(text).stream().findFirst();
    }

    public static List<String> all(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = PATH.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return List.copyOf(out);
    }
}
