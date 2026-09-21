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
import java.nio.file.StandardCopyOption;
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
     * 引导（bootstrap）用户知识库根：计算路径 + 播种种子 + 迁移历史双层嵌套。
     *
     * <p>三个 caller 都应当走这条入口：
     * <ul>
     *   <li>{@code KelsyRuntime.open(...)}</li>
     *   <li>{@code LocalAssistantService.create(KelsyConfig, String)}</li>
     *   <li>{@code LocalAssistantService.create(KelsyConfig, String, KnowledgeStore)}</li>
     * </ul>
     *
     * <p>幂等：
     * <ul>
     *   <li>种子写入只创建缺失项（{@link WorkspaceSeeder#seed} 内部用 writeIfAbsent）</li>
     *   <li>迁移在内层目录不存在或已为空时立即返回</li>
     * </ul>
     *
     * <p>为什么不放在 {@link WorkspaceSeeder} 里：迁移属于用户命名空间的额外补救，
     * 与「共享种子」语义不同；放 {@code KnowledgeStore} 是它本来就在管用户根。
     *
     * @param workspace 工作空间根目录（可为裸根；用户级目录会拼接到其下）
     * @param username  用户名（{@code null}/空 → 退化为裸 workspace；含分隔符抛异常）
     * @return 计算出的用户知识库根（{@code <workspace>/<username>/} 或裸 workspace）
     */
    public static Path bootstrap(Path workspace, String username) {
        Path knowledgeRoot = knowledgeRoot(workspace, username);
        WorkspaceSeeder.seed(knowledgeRoot);
        migrateLegacyDoubleNested(knowledgeRoot, username);
        return knowledgeRoot;
    }

    /**
     * 迁移历史遗留的双层嵌套用户根（{@code <userRoot>/<username>/} → {@code <userRoot>/}）。
     *
     * <p>2026-09-21 之前的实现里，旧 AgentScope 工作空间被错误地解析为
     * {@code <workspace>/<username>/<username>/}，导致用户今早写的会议纪要、日记、
     * MEMORY.md 等都困在嵌套的内层目录里。Java 端在把知识根收敛到
     * {@code <workspace>/<username>/} 之后，那批文件对新代码不可见，于是右侧知识库
     * 面板所有引用都回退到 {@link Read.Missing}。
     *
     * <p>本方法在启动时被调用一次：若内层目录存在，就把它的内容合并到外层，然后
     * 删除空的内层目录。规则：
     * <ul>
     *   <li>目标位置不存在 → 直接移动</li>
     *   <li>目标是文件、源是文件 → 内容相同则删源；不同则警告并保留双方</li>
     *   <li>目标是目录、源是目录 → 递归合并（同一规则）</li>
     *   <li>类型冲突（目录 vs 文件）→ 警告并跳过</li>
     * </ul>
     *
     * <p>幂等：内层目录不存在或已为空时立即返回。
     *
     * @param userRoot 用户知识库根目录（{@code <workspace>/<username>/}）
     * @param username 当前用户名（用于定位内层遗留目录；含路径分隔符抛异常）
     * @return true 表示确实迁移了至少一个条目
     * @throws IllegalArgumentException 用户名含路径分隔符
     */
    public static boolean migrateLegacyDoubleNested(Path userRoot, String username) {
        if (userRoot == null) {
            return false;
        }
        if (username == null || username.isBlank()) {
            return false;
        }
        if (containsPathSeparator(username)) {
            throw new IllegalArgumentException(
                    "username 不可包含路径分隔符：'" + username + "'");
        }
        Path root = userRoot.toAbsolutePath().normalize();
        Path legacy = root.resolve(username).normalize();
        if (!legacy.startsWith(root) || legacy.equals(root)) {
            return false;
        }
        if (!Files.isDirectory(legacy)) {
            return false;
        }
        try (var stream = Files.list(legacy)) {
            var children = stream.toList();
            if (children.isEmpty()) {
                Files.delete(legacy);
                Diagnostics.log("kelsy", "removed empty legacy dir %s", legacy);
                return false;
            }
            boolean moved = false;
            for (Path child : children) {
                moved |= mergeInto(child, root.resolve(child.getFileName()));
            }
            // 合并过程中遗留的内层空目录（递归合并产生的）也顺手清掉，否则后续 reconcile 仍能
            // 在旧路径上看到空目录条目，悬空感观不变。
            pruneEmptyDirs(legacy);
            if (!Files.exists(legacy)) {
                Diagnostics.log("kelsy", "removed legacy dir %s after migration", legacy);
            } else {
                Diagnostics.warn("kelsy", "legacy dir %s not empty after migration; left in place",
                        legacy);
            }
            return moved;
        } catch (IOException e) {
            Diagnostics.warn("kelsy", "legacy migration skipped for %s: %s", legacy, e.toString());
            return false;
        }
    }

    /**
     * 把 {@code source} 合并到 {@code target}（target 可能已存在）。详见
     * {@link #migrateLegacyDoubleNested(Path, String)} 的合并规则。
     *
     * <p>合并目录后会尝试删除递归产生的空子目录，避免在遗留根下残留空壳。
     */
    private static boolean mergeInto(Path source, Path target) throws IOException {
        boolean sourceIsDir = Files.isDirectory(source);
        boolean targetExists = Files.exists(target);
        if (!targetExists) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            Diagnostics.log("kelsy", "migrated %s -> %s", source, target);
            return true;
        }
        boolean targetIsDir = Files.isDirectory(target);
        if (sourceIsDir && targetIsDir) {
            boolean any = false;
            try (var stream = Files.list(source)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    any |= mergeInto(child, target.resolve(child.getFileName()));
                }
            }
            pruneEmptyDirs(source);
            return any;
        }
        if (sourceIsDir != targetIsDir) {
            Diagnostics.warn("kelsy",
                    "legacy merge skipped: type conflict %s (%s) vs %s (%s)",
                    source, sourceIsDir ? "dir" : "file",
                    target, targetIsDir ? "dir" : "file");
            return false;
        }
        // 都是文件：内容相同则删源
        if (Files.mismatch(source, target) == -1L) {
            Files.delete(source);
            Diagnostics.log("kelsy", "removed duplicate %s (identical to %s)", source, target);
            return true;
        }
        // 目标位置的内容是 WorkspaceSeeder 留下的空种子（典型表现为纯空白或只有一两个
        // 头行），而遗留目录里有真实内容时，直接以遗留版本覆盖种子，避免用户今早写的
        // MEMORY.md 索引被「新代码刚种子化的空文件」悄悄盖掉。
        if (isLikelySeedTemplate(target) && !isLikelySeedTemplate(source)) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            Diagnostics.warn("kelsy",
                    "legacy merge overwrote seed at %s (legacy had real content)", target);
            return true;
        }
        // 双方都有真实内容，无法判断哪个更权威 → 保留双方，遗留版本改名 .legacy 让用户人工取舍。
        Diagnostics.warn("kelsy",
                "legacy merge kept both: %s and %s differ; renaming legacy to .legacy",
                source, target);
        Path renamed = target.getParent().resolve(
                target.getFileName().toString() + ".legacy");
        int n = 1;
        while (Files.exists(renamed)) {
            renamed = target.getParent().resolve(
                    target.getFileName().toString() + ".legacy" + n++);
        }
        Files.move(source, renamed, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * 粗略判断一个文件是否仍是 WorkspaceSeeder 留下的「种子模板」状态。
     *
     * <p>判定：去除前后空白后与 {@code SEED_FILES} 中任一资源文件的 strip() 后内容
     * 完全一致即视为种子。其它情况（包括文件不存在、I/O 错误、内容指纹未匹配）一律
     * 视为「用户已有真实内容」，避免误判覆盖用户笔记。
     *
     * <p>这是为了在双层嵌套迁移时避免种子覆盖用户真实内容。误判只会退化到
     * 「保留双方」分支，不会丢数据。
     */
    private static boolean isLikelySeedTemplate(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return true;
            }
            String text = Files.readString(file).strip();
            for (String seed : seedFingerprints()) {
                if (text.equals(seed)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 需要按「种子指纹」比较的 WorkspaceSeeder 资源文件名（位于
     * {@code /com/mordor/kelly/kelsy/workspace/} 下）。资源原文被一次性读入缓存，
     * 避免每次合并都打 classpath。
     */
    private static final List<String> SEED_FILES = List.of(
            "MEMORY.md", "AGENTS.md", "KNOWLEDGE.md");

    /** 已 strip() 过的种子指纹缓存。{@code null} 表示尚未加载。 */
    private static volatile List<String> seedCache;

    private static List<String> seedFingerprints() {
        List<String> cached = seedCache;
        if (cached != null) {
            return cached;
        }
        List<String> loaded = new ArrayList<>(SEED_FILES.size());
        for (String name : SEED_FILES) {
            try (var in = KnowledgeStore.class.getResourceAsStream(
                    "/com/mordor/kelly/kelsy/workspace/" + name)) {
                if (in == null) {
                    continue;
                }
                loaded.add(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip());
            } catch (IOException e) {
                // 读不到指纹就当无指纹可用：所有现有内容都按「非种子」处理。
                Diagnostics.warn("kelsy", "seed fingerprint load failed for %s: %s",
                        name, e.toString());
            }
        }
        seedCache = List.copyOf(loaded);
        return seedCache;
    }

    /**
     * 递归删除目录树中的空目录（自底向上）。仅删除 {@code start} 自身及以下的空目录；
     * 不会触及 {@code start} 的兄弟节点。
     */
    private static void pruneEmptyDirs(Path start) {
        if (start == null || !Files.isDirectory(start)) {
            return;
        }
        try (var stream = Files.list(start)) {
            var children = stream.toList();
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    pruneEmptyDirs(child);
                }
            }
        } catch (IOException e) {
            return;
        }
        // 自底向上扫一遍：再次尝试删自己
        try (var stream = Files.list(start)) {
            if (stream.findAny().isEmpty()) {
                Files.delete(start);
            }
        } catch (IOException e) {
            // 目录仍有内容或权限问题，保留即可
        }
    }

    /**
     * 计算用户知识库的根目录路径。
     *
     * <p>用户名会被当成 <b>单层</b> 目录名附加到 workspace 下，<b>禁止</b>含任何路径分隔符
     * （{@code /} {@code \} {@code :}，或 NUL 等控制字符）。这是为了防止
     * {@code "ksw/ksw"} 这类嵌套用户名把知识根嵌成多层子目录，导致 Java 端
     * {@link com.mordor.kelly.kelsy.todo.TodoScanner} 与 AgentScope 助手写入路径不一致。
     *
     * @param workspace 工作空间根目录
     * @param username  用户名（可为 null 或空；含分隔符将抛 {@link IllegalArgumentException}）
     * @return 用户知识库根目录
     * @throws IllegalArgumentException 用户名含路径分隔符或控制字符
     */
    public static Path knowledgeRoot(Path workspace, String username) {
        Path base = workspace.toAbsolutePath().normalize();
        if (username == null || username.isBlank()) {
            return base;
        }
        String cleaned = username.strip();
        if (containsPathSeparator(cleaned)) {
            throw new IllegalArgumentException(
                    "username 不可包含路径分隔符，会导致知识根多层嵌套：'" + cleaned + "'");
        }
        Path named = base.resolve(cleaned).normalize();
        // 安全检查：确保不逃逸出工作空间
        if (!named.startsWith(base) || named.equals(base)) {
            return base;
        }
        return named;
    }

    /**
     * 判断字符串是否包含任何路径分隔符或控制字符。
     *
     * <p>匹配 {@code /}、{@code \}、{@code :}（Windows 盘符冒号）以及 ASCII 控制字符。
     */
    private static boolean containsPathSeparator(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c < 0x20) {
                return true;
            }
        }
        return false;
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
