/**
 * 知识库存储服务。
 *
 * <p>管理用户本地知识库的文件系统操作，包括：
 * <ul>
 *   <li>列出知识库中的文件（规范、索引、日记、知识等）</li>
 *   <li>读取单个知识文件内容</li>
 *   <li>全文搜索知识库</li>
 *   <li>根据关键词查找相关知识卡片</li>
 * </ul>
 *
 * <p>知识库目录结构：
 * <pre>
 *   workspace/
 *   ├── AGENTS.md          ← AI 助手行为规范（规范）
 *   ├── MEMORY.md          ← 记忆索引（索引）
 *   ├── memory/            ← 日记目录（按日期存储）
 *   │   ├── 2026-01-15.md
 *   │   └── ...
 *   └── knowledge/         ← 知识库
 *       ├── KNOWLEDGE.md   ← 知识索引
 *       ├── people/        ← 人员信息
 *       ├── projects/      ← 项目信息
 *       ├── playbooks/     ← 操作手册
 *       ├── inbox/         ← 收件箱
 *       ├── meetings/      ← 会议卡片
 *       ├── decisions/     ← 决策卡片
 *       └── todos/         ← 待办卡片
 * </pre>
 *
 * <p>安全措施：
 * <ul>
 *   <li>路径遍历防护：禁止 {@code ..}、绝对路径、冒号等</li>
 *   <li>文件大小限制：单文件最大 256KB</li>
 *   <li>搜索结果上限：最多返回 50 条</li>
 * </ul>
 *
 * <p>用户隔离：每个用户有独立的知识库子目录（{@code workspace/username/}）。
 *
 * @see FindQuery
 * @see KnowledgePathExtractor
 */
package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.common.Diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record KnowledgeStore(Path workspace, KnowledgeIndex index) implements AutoCloseable {

    /** 单个文件最大字节数（256KB） */
    public static final long MAX_FILE_BYTES = 256L * 1024;

    /** MEMORY.md 警告阈值（8KB），超过时 UI 显示警告 */
    public static final long MEMORY_WARN_BYTES = 8L * 1024;

    /** 搜索结果最大返回数量 */
    public static final int MAX_HITS = 50;

    /**
     * 知识库条目：表示一个文件或目录。
     *
     * @param label       显示标签（如 "规范"、"索引"、"日记"）
     * @param relativePath 相对于工作空间的路径
     * @param directory   是否为目录
     * @param children    子条目列表（目录才有）
     */
    public record Entry(String label, String relativePath, boolean directory, List<Entry> children) {
    }

    /**
     * 搜索命中结果。
     *
     * @param relativePath 匹配文件的相对路径
     * @param line         匹配行号（1-indexed）
     * @param snippet      匹配行的摘要文本（截断到 120 字符）
     */
    public record Hit(String relativePath, int line, String snippet, double score) {
        public Hit(String relativePath, int line, String snippet) {
            this(relativePath, line, snippet, 0);
        }
    }

    /**
     * 文件读取结果（密封类模式匹配）。
     * 使用 Java 密封类实现类型安全的多态返回。
     */
    public sealed interface Read {
        /** 读取成功 */
        record Ok(String relativePath, String markdown) implements Read {
        }

        /** 文件不存在 */
        record Missing(String relativePath) implements Read {
        }

        /** 文件过大（超过 256KB） */
        record TooLarge(String relativePath, long bytes) implements Read {
        }

        /** 读取被拒绝（路径不安全等） */
        record Rejected(String reason) implements Read {
        }
    }

    /**
     * 规范化工作空间路径。{@code index} 允许为 null（打开失败时走扫描回退）。
     */
    public KnowledgeStore {
        workspace = workspace.toAbsolutePath().normalize();
    }

    /**
     * 构造函数：规范化工作空间并尝试打开 FTS 索引。
     */
    public KnowledgeStore(Path workspace) {
        this(workspace, tryOpen(workspace));
    }

    private static KnowledgeIndex tryOpen(Path workspace) {
        try {
            return KnowledgeIndex.open(workspace);
        } catch (RuntimeException e) {
            Diagnostics.warn("kelsy", "knowledge index unavailable, falling back to scan: %s", e.toString());
            return null;
        }
    }

    @Override
    public void close() {
        if (index != null) {
            index.close();
        }
    }

    /**
     * 为指定用户创建知识库实例。
     * 用户知识库位于工作空间下的用户子目录中。
     *
     * @param workspace 工作空间根目录
     * @param username  用户名
     * @return 该用户的知识库实例
     */
    public static KnowledgeStore forUser(Path workspace, String username) {
        return new KnowledgeStore(knowledgeRoot(workspace, username));
    }

    /**
     * 计算用户知识库的根目录路径。
     *
     * @param workspace 工作空间根目录
     * @param username  用户名（可为 null 或空）
     * @return 用户知识库根目录
     */
    public static Path knowledgeRoot(Path workspace, String username) {
        Path base = workspace.toAbsolutePath().normalize();
        if (username == null || username.isBlank()) {
            return base;
        }
        Path named = base.resolve(username.strip()).normalize();
        // 安全检查：确保不逃逸出工作空间
        if (!named.startsWith(base) || named.equals(base)) {
            return base;
        }
        return named;
    }

    /**
     * 列出知识库的主要条目。
     *
     * @return 条目列表：规范(AGENTS.md)、索引(MEMORY.md)、日记、知识
     */
    public List<Entry> list() {
        List<Entry> roots = new ArrayList<>();
        roots.add(fileEntry("规范", "AGENTS.md"));
        roots.add(fileEntry("索引", "MEMORY.md"));
        roots.add(new Entry("日记", "memory", true, listMarkdown("memory")));
        roots.add(new Entry("知识", "knowledge", true, listMarkdown("knowledge")));
        return roots;
    }

    /**
     * 读取知识库中的文件内容。
     *
     * @param relativePath 相对于工作空间的路径
     * @return 读取结果（Ok/Missing/TooLarge/Rejected）
     */
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

    /**
     * 获取 MEMORY.md 文件的大小（字节）。
     * 用于 UI 显示警告（超过 8KB 时提示用户整理）。
     *
     * @return 文件大小，文件不存在时返回 0
     */
    public long memoryBytes() {
        Path p = workspace.resolve("MEMORY.md");
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 获取默认打开的知识文件路径。
     * 优先级：knowledge/KNOWLEDGE.md > AGENTS.md
     *
     * @return 默认路径，或 null（无可用文件）
     */
    public String defaultPath() {
        if (Files.isRegularFile(workspace.resolve("knowledge/KNOWLEDGE.md"))) {
            return "knowledge/KNOWLEDGE.md";
        }
        if (Files.isRegularFile(workspace.resolve("AGENTS.md"))) {
            return "AGENTS.md";
        }
        return null;
    }

    /**
     * 全文搜索知识库。
     *
     * <p>搜索范围：
     * <ol>
     *   <li>MEMORY.md（记忆索引）</li>
     *   <li>memory/ 目录下的日记文件（按日期过滤）</li>
     *   <li>knowledge/ 目录下的所有 .md 文件</li>
     * </ol>
     *
     * <p>搜索逻辑：优先 {@link KnowledgeIndex#reconcileIfStale()} + FTS；索引失败则逐行扫描，
     * 要求所有关键词都匹配（AND 逻辑）。
     *
     * @param query 搜索查询（包含日期范围和关键词）
     * @return 匹配结果列表（最多 50 条）
     */
    public List<Hit> search(FindQuery query) {
        return search(query, false);
    }

    /**
     * @param forceReconcile true 时全盘对账（模型刚写完卡后的补查）
     */
    public List<Hit> search(FindQuery query, boolean forceReconcile) {
        if (index != null) {
            try {
                if (forceReconcile) {
                    index.reconcile();
                } else {
                    index.reconcileIfStale();
                }
                return index.search(query, MAX_HITS);
            } catch (RuntimeException ignored) {
            }
        }
        return scanSearch(query);
    }

    /**
     * 按相对路径增量更新索引。写卡之后应调用，避免下一次检索再全盘 reconcile。
     */
    public void upsert(String relativePath) {
        if (index != null && relativePath != null && !relativePath.isBlank()) {
            index.upsert(relativePath);
        }
    }

    /**
     * 扫描回退：walk + {@link #scanFile}，与索引无关。
     */
    private List<Hit> scanSearch(FindQuery query) {
        List<Hit> hits = new ArrayList<>();
        boolean keywordsEmpty = query.keywords().isEmpty();
        // 搜索 MEMORY.md
        if (!keywordsEmpty) {
            scanFile(workspace.resolve("MEMORY.md"), "MEMORY.md", query, hits);
        }
        // 搜索 memory/ 目录下的日记文件
        Path mem = workspace.resolve("memory");
        if (Files.isDirectory(mem)) {
            try (var stream = Files.list(mem)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> query.matchesDailyFile(p.getFileName().toString()))
                        .forEach(p -> scanFile(p, rel(p), query, hits));
            } catch (IOException ignored) {
            }
        }
        // 搜索 knowledge/ 目录下的所有 .md 文件
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

    /**
     * 获取指定目录下的所有 Markdown 文件路径。
     *
     * @param relativeDir 相对于工作空间的目录路径
     * @return 文件相对路径列表
     */
    public List<String> cardPaths(String relativeDir) {
        return listMarkdown(relativeDir).stream().map(Entry::relativePath).toList();
    }

    /**
     * 查找包含指定关键词的知识卡片。
     *
     * @param terms 关键词列表（至少 2 字符）
     * @return 匹配的文件相对路径列表
     */
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
        if (index != null) {
            try {
                index.reconcileIfStale();
                Set<String> paths = new LinkedHashSet<>();
                // OR any needle: /find search() stays AND via FindQuery MATCH.
                for (String needle : needles) {
                    List<Hit> hits = index.search(
                            new FindQuery(null, null, List.of(needle)), Integer.MAX_VALUE);
                    for (Hit hit : hits) {
                        if (!isKnowledgeCard(hit.relativePath())) {
                            continue;
                        }
                        paths.add(hit.relativePath());
                        if (paths.size() >= MAX_HITS) {
                            return List.copyOf(paths);
                        }
                    }
                }
                return List.copyOf(paths);
            } catch (RuntimeException ignored) {
            }
        }
        return scanCardsContaining(needles);
    }

    /** Catalog index page; indexed, but excluded from shown citations and card lists. */
    static boolean isKnowledgeCatalog(String path) {
        return "knowledge/KNOWLEDGE.md".equals(path);
    }

    /** L0 已注入上下文，预检索不再当卡片附上。 */
    static boolean isAskCandidateExcluded(String path) {
        return isKnowledgeCatalog(path) || "MEMORY.md".equals(path);
    }

    /** Same scope as {@link #scanCardsContaining}: knowledge cards, not the index file. */
    static boolean isKnowledgeCard(String path) {
        return path != null && path.startsWith("knowledge/") && !isKnowledgeCatalog(path);
    }

    private List<String> scanCardsContaining(List<String> needles) {
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

    /**
     * 检查文件是否包含指定关键词，如果包含则添加到结果列表。
     */
    private void addIfContains(Path path, List<String> needles, List<String> out) {
        if (out.size() >= MAX_HITS) {
            return;
        }
        String rel = rel(path);
        if (!isKnowledgeCard(rel)) {
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

    /** 创建文件条目 */
    private Entry fileEntry(String label, String relative) {
        return new Entry(label, relative, false, List.of());
    }

    /**
     * 列出指定目录下的所有 Markdown 文件。
     */
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

    /** 获取相对路径（统一使用正斜杠） */
    private String rel(Path p) {
        return workspace.relativize(p).toString().replace('\\', '/');
    }

    /**
     * 扫描单个文件，查找匹配查询条件的行。
     */
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
                // 所有关键词都匹配（AND 逻辑）
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

    /**
     * 安全地解析相对路径：防止路径遍历攻击。
     *
     * @param relativePath 相对路径
     * @return 解析后的绝对路径，不安全时返回 null
     */
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
