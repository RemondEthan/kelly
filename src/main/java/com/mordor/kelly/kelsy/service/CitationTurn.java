package com.mordor.kelly.kelsy.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CitationTurn {

    private static final Set<String> RETRIEVAL = Set.of(
            "memory_get", "memory_search", "read_file", "list_files");

    private final List<String> shown = new ArrayList<>();
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();
    private String currentTool = "";
    private final StringBuilder toolArgs = new StringBuilder();
    private final StringBuilder toolResult = new StringBuilder();

    public static boolean isRetrievalTool(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return RETRIEVAL.contains(name.strip().toLowerCase(Locale.ROOT));
    }

    public void beginAsk() {
        pending.clear();
        resetTool();
    }

    public void beginTool(String name) {
        currentTool = name == null ? "" : name;
        toolArgs.setLength(0);
        toolResult.setLength(0);
    }

    public void appendToolArgs(String delta) {
        if (delta != null && !delta.isEmpty()) {
            toolArgs.append(delta);
        }
        extractCurrent();
    }

    public void appendToolResult(String delta) {
        if (delta != null && !delta.isEmpty()) {
            toolResult.append(delta);
        }
        extractCurrent();
    }

    public List<String> pendingPaths() {
        return List.copyOf(pending);
    }

    public String toolArgs() {
        return toolArgs.toString();
    }

    public String toolText() {
        return toolArgs + "\n" + toolResult;
    }

    private void extractCurrent() {
        if (!isRetrievalTool(currentTool)) {
            return;
        }
        addRetrievalText(toolArgs + "\n" + toolResult);
    }

    private void resetTool() {
        currentTool = "";
        toolArgs.setLength(0);
        toolResult.setLength(0);
    }

    public void addRetrievalText(String text) {
        pending.addAll(KnowledgePathExtractor.all(text));
    }

    public void addPaths(List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            if (p != null && !p.isBlank()) {
                pending.add(p);
            }
        }
    }

    public boolean commitIfRetrieved() {
        if (pending.isEmpty()) {
            return false;
        }
        shown.clear();
        shown.addAll(pending);
        pending.clear();
        return true;
    }

    public List<String> shown() {
        return List.copyOf(shown);
    }

    public String lastShown() {
        return shown.isEmpty() ? null : shown.get(shown.size() - 1);
    }

    public String evidencePath() {
        for (int i = shown.size() - 1; i >= 0; i--) {
            String path = shown.get(i);
            if (isEvidenceCard(path)) {
                return path;
            }
        }
        return lastShown();
    }

    private static boolean isEvidenceCard(String path) {
        return path.startsWith("knowledge/") && !path.equals("knowledge/KNOWLEDGE.md");
    }

    public String firstShown() {
        return shown.isEmpty() ? null : shown.get(0);
    }

    public void replaceShown(List<String> paths) {
        shown.clear();
        addAllShown(paths);
    }

    private void addAllShown(List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            if (p != null && !p.isBlank()) {
                shown.add(p);
            }
        }
    }
}
