package com.mordor.kelly.kelsy.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public record KnowledgeStore(Path workspace) {

    public static final long MAX_FILE_BYTES = 256L * 1024;
    public static final long MEMORY_WARN_BYTES = 8L * 1024;
    public static final int MAX_HITS = 50;

    public record Entry(String label, String relativePath, boolean directory, List<Entry> children) {
    }

    public record Hit(String relativePath, int line, String snippet) {
    }

    public sealed interface Read {
        record Ok(String relativePath, String markdown) implements Read {
        }

        record Missing(String relativePath) implements Read {
        }

        record TooLarge(String relativePath, long bytes) implements Read {
        }

        record Rejected(String reason) implements Read {
        }
    }

    public KnowledgeStore(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    public static KnowledgeStore forUser(Path workspace, String username) {
        return new KnowledgeStore(knowledgeRoot(workspace, username));
    }

    public static Path knowledgeRoot(Path workspace, String username) {
        Path base = workspace.toAbsolutePath().normalize();
        if (username == null || username.isBlank()) {
            return base;
        }
        Path named = base.resolve(username.strip()).normalize();
        if (!named.startsWith(base) || named.equals(base)) {
            return base;
        }
        return named;
    }

    public List<Entry> list() {
        List<Entry> roots = new ArrayList<>();
        roots.add(fileEntry("规范", "AGENTS.md"));
        roots.add(fileEntry("索引", "MEMORY.md"));
        roots.add(new Entry("日记", "memory", true, listMarkdown("memory")));
        roots.add(new Entry("知识", "knowledge", true, listMarkdown("knowledge")));
        return roots;
    }

    public Read read(String relativePath) {
        Path resolved = resolveSafe(relativePath);
        if (resolved == null) {
            return new Read.Rejected("路径不在知识库内");
        }
        if (!Files.isRegularFile(resolved)) {
            return new Read.Missing(relativePath);
        }
        try {
            long size = Files.size(resolved);
            if (size > MAX_FILE_BYTES) {
                return new Read.TooLarge(relativePath, size);
            }
            return new Read.Ok(relativePath, Files.readString(resolved));
        } catch (IOException e) {
            return new Read.Rejected(e.getMessage() == null ? "读取失败" : e.getMessage());
        }
    }

    public long memoryBytes() {
        Path p = workspace.resolve("MEMORY.md");
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    public String defaultPath() {
        if (Files.isRegularFile(workspace.resolve("knowledge/KNOWLEDGE.md"))) {
            return "knowledge/KNOWLEDGE.md";
        }
        if (Files.isRegularFile(workspace.resolve("AGENTS.md"))) {
            return "AGENTS.md";
        }
        return null;
    }

    public List<Hit> search(FindQuery query) {
        List<Hit> hits = new ArrayList<>();
        boolean keywordsEmpty = query.keywords().isEmpty();
        if (!keywordsEmpty) {
            scanFile(workspace.resolve("MEMORY.md"), "MEMORY.md", query, hits);
        }
        Path mem = workspace.resolve("memory");
        if (Files.isDirectory(mem)) {
            try (var stream = Files.list(mem)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> query.matchesDailyFile(p.getFileName().toString()))
                        .forEach(p -> scanFile(p, rel(p), query, hits));
            } catch (IOException ignored) {
            }
        }
        if (!keywordsEmpty) {
            Path knowledge = workspace.resolve("knowledge");
            if (Files.isDirectory(knowledge)) {
                try (var stream = Files.walk(knowledge)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                            .forEach(p -> scanFile(p, rel(p), query, hits));
                } catch (IOException ignored) {
                }
            }
        }
        return hits.size() > MAX_HITS ? List.copyOf(hits.subList(0, MAX_HITS)) : List.copyOf(hits);
    }

    public List<String> cardPaths(String relativeDir) {
        return listMarkdown(relativeDir).stream().map(Entry::relativePath).toList();
    }

    public List<String> cardsContaining(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        List<String> needles = terms.stream()
                .filter(t -> t != null && t.strip().length() >= 2)
                .map(t -> t.strip().toLowerCase(Locale.ROOT))
                .toList();
        if (needles.isEmpty()) {
            return List.of();
        }
        Path knowledge = workspace.resolve("knowledge");
        if (!Files.isDirectory(knowledge)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (var stream = Files.walk(knowledge)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted(Comparator.comparing(p -> workspace.relativize(p).toString()))
                    .forEach(p -> addIfContains(p, needles, out));
        } catch (IOException ignored) {
        }
        return List.copyOf(out);
    }

    private void addIfContains(Path path, List<String> needles, List<String> out) {
        if (out.size() >= MAX_HITS) {
            return;
        }
        String rel = rel(path);
        if (rel.equals("knowledge/KNOWLEDGE.md")) {
            return;
        }
        String hay = rel;
        try {
            if (Files.size(path) <= MAX_FILE_BYTES) {
                hay = rel + "\n" + Files.readString(path);
            }
        } catch (IOException ignored) {
        }
        String lower = hay.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle)) {
                out.add(rel);
                return;
            }
        }
    }

    private Entry fileEntry(String label, String relative) {
        return new Entry(label, relative, false, List.of());
    }

    private List<Entry> listMarkdown(String relativeDir) {
        Path dir = workspace.resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted(Comparator.comparing(p -> workspace.relativize(p).toString()))
                    .map(p -> {
                        String rel = workspace.relativize(p).toString().replace('\\', '/');
                        return new Entry(p.getFileName().toString(), rel, false, List.of());
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String rel(Path p) {
        return workspace.relativize(p).toString().replace('\\', '/');
    }

    private void scanFile(Path path, String relative, FindQuery query, List<Hit> hits) {
        if (hits.size() >= MAX_HITS || !Files.isRegularFile(path)) {
            return;
        }
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                return;
            }
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                String lower = line.toLowerCase(Locale.ROOT);
                boolean all = query.keywords().isEmpty()
                        || query.keywords().stream().allMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
                if (all) {
                    String snippet = line.strip();
                    if (snippet.length() > 120) {
                        snippet = snippet.substring(0, 120) + "…";
                    }
                    hits.add(new Hit(relative, i + 1, snippet));
                    if (hits.size() >= MAX_HITS) {
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private Path resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        String norm = relativePath.replace('\\', '/');
        if (norm.startsWith("/") || norm.contains("..") || norm.contains(":")) {
            return null;
        }
        Path resolved = workspace.resolve(norm).normalize();
        if (!resolved.startsWith(workspace)) {
            return null;
        }
        return resolved;
    }
}
