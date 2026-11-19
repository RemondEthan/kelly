package com.mordor.kelly.kelsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mordor.kelly.kelsy.storage.HybridBackupService;
import com.mordor.kelly.kelsy.todo.*;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Explicit migration within one user's knowledge root. The immutable plan is the recovery journal. */
final class LegacyKnowledgeMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIRECTORY = ".kelly-migration/";
    private static final int PAGE_SIZE = 20;
    private static final Pattern FIELD = Pattern.compile("^- (标题|截止|状态|优先级|完成时间|来源)\\s*[：:]\\s*(.*)$");
    private static final Pattern SOURCE = Pattern.compile("(?:\\[[^]]*]\\()?((?:knowledge/)[^()\\r\\n]+\\.md)\\)?");
    private static final Map<String, String> KINDS = Map.of(
            "meetings", "meeting", "decisions", "decision", "people", "person",
            "projects", "project", "playbooks", "playbook", "inbox", "inbox");

    enum Kind { ARTICLE, INDEXED, TODO, PRESERVE, REVIEW }
    record Entry(String path, String hash, Kind kind, UUID uid, String title, String type,
                 TodoStatus status, String due, int priority, String completed, String source, String issue) {}
    record Plan(UUID batch, List<Entry> entries) {}
    record Item(Entry entry, String state, String detail) {}
    record Report(UUID batch, String archive, String state, Map<String, Long> counts,
                  List<Item> items, Integer nextOffset) {}

    private final KnowledgeStore store;
    private final MarkdownDocumentStore files;

    LegacyKnowledgeMigration(KnowledgeStore store) {
        this.store = store;
        this.files = new MarkdownDocumentStore(store.workspace());
    }

    synchronized Report plan() throws Exception {
        List<Entry> entries = new ArrayList<>();
        for (String path : sourcePaths()) {
            String hash = null;
            try {
                hash = fileHash(files.safe(path));
                String raw = path.equals(".todo-reminder-ledger") ? "" : files.readText(path);
                if (!path.equals(".todo-reminder-ledger") && !hash.equals(MarkdownDocumentStore.hash(raw))) {
                    throw new IOException("扫描期间源文件已变更");
                }
                entries.add(classify(path, hash, raw));
            } catch (Exception failure) {
                entries.add(new Entry(path, hash, Kind.REVIEW, null, null, null, null,
                        null, 0, null, null, message(failure)));
            }
        }
        var ids = new HashMap<UUID, Long>();
        for (Entry entry : entries) {
            if (entry.uid() != null && entry.kind() != Kind.TODO) ids.merge(entry.uid(), 1L, Long::sum);
        }
        entries.replaceAll(entry -> ids.getOrDefault(entry.uid(), 0L) > 1
                ? new Entry(entry.path(), entry.hash(), Kind.REVIEW, entry.uid(), entry.title(), entry.type(),
                            null, null, 0, null, null, "文档 UUID 重复")
                : entry);
        var titles = new HashMap<String, Long>();
        for (Entry entry : entries) {
            if (entry.kind() == Kind.TODO) titles.merge(entry.title(), 1L, Long::sum);
        }
        entries.replaceAll(entry -> entry.kind() == Kind.TODO && titles.get(entry.title()) > 1
                ? new Entry(entry.path(), entry.hash(), entry.kind(), entry.uid(), entry.title(), entry.type(),
                            entry.status(), entry.due(), entry.priority(), entry.completed(), entry.source(),
                            entry.issue() == null ? "存在同名旧待办，按不同原件分别导入" : entry.issue() + "。存在同名旧待办")
                : entry);
        Plan plan = new Plan(UUID.randomUUID(), List.copyOf(entries));
        save(planPath(plan.batch()), plan);
        return preview(plan.batch(), 0);
    }

    synchronized Report preview(UUID batch, int offset) throws IOException {
        Plan plan = load(batch);
        return page(plan, "preview", plan.entries().stream().map(entry -> new Item(entry,
                entry.kind() == Kind.REVIEW ? "review" : "planned", entry.issue())).toList(), offset);
    }

    synchronized Report apply(UUID batch) throws Exception {
        Plan plan = load(batch);
        Path archive = archive(batch);
        if (!Files.exists(archive)) {
            if (Files.exists(reportPath(batch))) throw new IOException("原件归档已丢失，请恢复原归档");
            if (!sourcePaths().equals(plan.entries().stream().map(Entry::path).toList())) {
                throw new IOException("源文件清单已变更，请重新预览");
            }
            for (Entry entry : plan.entries()) {
                if (entry.hash() != null && !entry.hash().equals(fileHash(files.safe(entry.path())))) {
                    throw new IOException("源文件已变更，请重新预览：" + entry.path());
                }
            }
            HybridBackupService.backup(store, archive);
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            requireSnapshotDatabase(zip);
            // Check the whole snapshot before mutating any active document or task.
            for (Entry entry : plan.entries()) {
                if (entry.hash() != null && !entry.hash().equals(archiveHash(zip, entry.path()))) {
                    throw new IOException("快照原件哈希不符：" + entry.path());
                }
            }
            List<Item> results = new ArrayList<>();
            for (Entry entry : plan.entries()) {
                try {
                    applyEntry(zip, entry);
                    results.add(verifyEntry(zip, entry));
                } catch (Exception failure) {
                    results.add(new Item(entry, "failed", message(failure)));
                }
            }
            save(reportPath(batch), results);
            return page(plan, completion(results), results, 0);
        }
    }

    synchronized Report verify(UUID batch, int offset) throws Exception {
        Plan plan = load(batch);
        List<Item> results = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive(batch).toFile())) {
            requireSnapshotDatabase(zip);
            for (Entry entry : plan.entries()) {
                try {
                    results.add(verifyEntry(zip, entry));
                } catch (Exception failure) {
                    results.add(new Item(entry, "failed", message(failure)));
                }
            }
        }
        return page(plan, completion(results), results, offset);
    }

    private Entry classify(String path, String hash, String raw) {
        if (path.startsWith("knowledge/todos/")) return task(path, hash, raw);
        if (path.toLowerCase(Locale.ROOT).endsWith(".md") && !path.endsWith(".md")) {
            throw new IllegalArgumentException("文件扩展名需人工确认，已保留原路径与内容");
        }
        if (path.equals("MEMORY.md") || path.startsWith("memory/")) {
            return new Entry(path, hash, Kind.INDEXED, systemUid(path), null, null, null, null, 0, null, null, null);
        }
        if (path.equals(".todo-reminder-ledger") || path.equals("knowledge/KNOWLEDGE.md") || path.equals("KNOWLEDGE.md")) {
            return new Entry(path, hash, Kind.PRESERVE, null, null, null, null, null, 0, null, null, null);
        }
        if (!path.startsWith("knowledge/")) throw new IllegalArgumentException("未识别的文档路径");
        if (raw.replace("\r\n", "\n").startsWith("\uFEFF---\n")) {
            throw new IllegalArgumentException("带 BOM 的旧元数据需要人工核对");
        }
        if (raw.replace("\r\n", "\n").startsWith("---\n")) {
            var article = MarkdownDocumentStore.parse(path, raw);
            return new Entry(path, hash, Kind.INDEXED, article.uid(), article.title(), article.kind(),
                    null, null, 0, null, null, null);
        }
        String folder = path.split("/", 3)[1];
        String title = CardFields.parse(path, raw).title();
        Entry entry = new Entry(path, hash, Kind.ARTICLE, uid("article", path), title,
                KINDS.getOrDefault(folder, "article"), null, null, 0, null, null, null);
        String output = articleText(entry, raw);
        MarkdownDocumentStore.parse(path, output);
        if (output.getBytes(StandardCharsets.UTF_8).length > KnowledgeStore.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("补齐元数据后超过文章大小上限");
        }
        return entry;
    }

    private Entry task(String path, String hash, String raw) {
        Map<String, String> fields = new HashMap<>();
        String heading = null;
        boolean fenced = false;
        for (String line : raw.lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) continue;
            if (heading == null && stripped.startsWith("# ")) heading = stripped.substring(2);
            var field = FIELD.matcher(stripped);
            if (field.matches() && fields.putIfAbsent(field.group(1), field.group(2).strip()) != null) {
                throw new IllegalArgumentException("旧待办字段重复：" + field.group(1));
            }
        }
        TodoStatus status = TodoCardParser.parseStatus(fields.get("状态"));
        if (status == null) throw new IllegalArgumentException("旧待办状态缺失或无法识别");
        String due = fields.getOrDefault("截止", "");
        if (due.isBlank() || due.equals("未设置") || due.equals("无")) due = null;
        else {
            if (due.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}.*")) due = due.substring(0, 10);
            due = LocalDate.parse(due).toString();
        }
        int priority = Integer.parseInt(fields.getOrDefault("优先级", "0"));
        if (priority < 0 || priority > 3) throw new IllegalArgumentException("旧待办优先级超出 0 到 3");
        String finished = fields.get("完成时间");
        if (finished != null && !finished.isBlank()) finished = Instant.parse(finished).toString();
        else finished = null;
        if (status == TodoStatus.OPEN && finished != null) throw new IllegalArgumentException("开放待办包含完成时间");
        String title = TodoCardParser.titleOf(fields.get("标题"), heading, path);
        if (title.isBlank() || title.codePointCount(0, title.length()) > 200) throw new IllegalArgumentException("旧待办标题无效");
        String link = fields.get("来源");
        if (link != null && !link.isBlank()) {
            var match = SOURCE.matcher(link);
            if (!match.matches() || !KnowledgeIndex.indexable(match.group(1))) {
                throw new IllegalArgumentException("来源字段需要人工核对");
            }
            link = match.group(1);
            files.safe(link);
        } else link = null;
        var mapping = store.todos().legacyImport(path);
        if (mapping.isPresent() && !mapping.get().sourceHash().equals(hash)) {
            throw new IllegalArgumentException("旧卡片已变更，与已导入版本冲突");
        }
        UUID uid = mapping.map(TodoRepository.LegacyImport::uid).orElseGet(() -> uid("todo", path));
        return new Entry(path, hash, Kind.TODO, uid, title, null, status, due, priority, finished, link,
                mapping.isPresent() ? "已有导入映射，执行时复核目标" : status == TodoStatus.CLOSED && finished == null ? "历史完成时间未知" : null);
    }

    private void applyEntry(ZipFile zip, Entry entry) throws Exception {
        if (entry.kind() == Kind.REVIEW || entry.kind() == Kind.PRESERVE) return;
        String current = files.readText(entry.path());
        if (entry.kind() == Kind.ARTICLE) {
            String output = articleText(entry, original(zip, entry));
            if (!output.equals(current)) files.update(entry.path(), entry.hash(), output);
        } else if (!MarkdownDocumentStore.hash(current).equals(entry.hash())) {
            throw new IOException("源文件已变更");
        }
        if (entry.kind() == Kind.TODO) {
            store.todos().importLegacy(entry.path(), entry.hash(), entry.uid(),
                    input(entry, current), entry.status(), completed(entry));
        } else {
            if (store.index() == null) throw new IOException("知识索引不可用");
            store.index().upsert(entry.path());
        }
    }

    private Item verifyEntry(ZipFile zip, Entry entry) throws Exception {
        if (entry.hash() == null) return new Item(entry, "failed", "无法核对原件：" + entry.issue());
        if (entry.kind() == Kind.PRESERVE || entry.kind() == Kind.REVIEW) {
            if (!entry.hash().equals(archiveHash(zip, entry.path()))
                    || !entry.hash().equals(fileHash(files.safe(entry.path())))) {
                throw new IOException("原件哈希不符");
            }
            return entry.kind() == Kind.PRESERVE ? new Item(entry, "verified", "原位保留")
                    : new Item(entry, "review", entry.issue());
        }
        String original = original(zip, entry);
        String current = files.readText(entry.path());
        String expected = entry.kind() == Kind.ARTICLE ? articleText(entry, original) : original;
        if (!current.equals(expected)) throw new IOException("当前文件与计划内容不符");
        switch (entry.kind()) {
            case REVIEW -> { return new Item(entry, "review", entry.issue()); }
            case ARTICLE, INDEXED -> {
                if (store.index() == null || !store.index().matchesDocument(entry.path(), entry.uid(),
                        MarkdownDocumentStore.hash(current))) throw new IOException("索引中的路径、UUID 或哈希未就绪");
            }
            case TODO -> {
                var mapping = store.todos().legacyImport(entry.path());
                if (!mapping.equals(Optional.of(new TodoRepository.LegacyImport(entry.uid(), entry.hash())))) {
                    throw new IOException("待办迁移映射缺失或不符");
                }
                Todo todo = store.todos().get(entry.uid());
                var intended = input(entry, original);
                if (!todo.title().equals(intended.title()) || !todo.descriptionMd().equals(intended.descriptionMd())
                        || todo.status() != entry.status() || !Objects.equals(todo.dueDate(), intended.dueDate())
                        || todo.priority() != intended.priority() || !Objects.equals(todo.completedAt(), completed(entry))
                        || !todo.sources().equals(intended.sources())) throw new IOException("正式待办与源卡片字段不符");
            }
            case PRESERVE -> { }
        }
        return new Item(entry, "verified", entry.issue());
    }

    private TodoRepository.Input input(Entry entry, String original) {
        List<Todo.Source> links = List.of();
        if (entry.source() != null) {
            var article = files.read(entry.source(), null);
            links = List.of(new Todo.Source(article.uid(), article.path(), article.hash(), null));
        }
        return new TodoRepository.Input(entry.title(), original,
                entry.due() == null ? null : LocalDate.parse(entry.due()), entry.priority(), links);
    }

    private static Instant completed(Entry entry) {
        return entry.completed() == null ? null : Instant.ofEpochMilli(Instant.parse(entry.completed()).toEpochMilli());
    }

    private static String articleText(Entry entry, String body) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("id", entry.uid().toString());
        meta.put("type", entry.type());
        meta.put("title", entry.title());
        var legacy = CardFields.parse(entry.path(), body);
        String date = legacyField(body, "日期").orElse("");
        if (date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                meta.put("date", LocalDate.parse(date).toString());
            } catch (java.time.format.DateTimeParseException ignored) {
                // The ambiguous date stays in the archived and active Markdown body.
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>(meta);
        addMetadataList(metadata, "aliases", legacy.aliases());
        legacyField(body, "标签").ifPresent(tags -> addMetadataList(metadata, "tags", tags));
        return "---\n" + new Yaml().dump(metadata) + "---\n" + body;
    }

    private static Optional<String> legacyField(String body, String key) {
        Pattern field = Pattern.compile("^- " + key + "\\s*[：:]\\s*(.+)$");
        String value = null;
        for (String line : body.lines().toList()) {
            if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) break;
            var match = field.matcher(line.strip());
            if (match.matches()) {
                if (value != null) return Optional.empty();
                value = match.group(1);
            }
        }
        return Optional.ofNullable(value);
    }

    private static void addMetadataList(Map<String, Object> metadata, String key, String field) {
        List<String> values = Arrays.stream(field.split("[,，、]")).map(String::strip)
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (!values.isEmpty() && values.size() <= 32
                && values.stream().allMatch(value -> value.codePointCount(0, value.length()) <= 100)) {
            metadata.put(key, values);
        }
    }

    private List<String> sourcePaths() throws IOException {
        try (var paths = Files.walk(store.workspace())) {
            return paths.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> store.workspace().relativize(path).toString().replace('\\', '/'))
                    .filter(path -> path.equals(".todo-reminder-ledger") || path.equals("MEMORY.md")
                            || path.equals("KNOWLEDGE.md") || (path.toLowerCase(Locale.ROOT).endsWith(".md")
                            && (path.startsWith("knowledge/") || path.startsWith("memory/"))))
                    .sorted(Comparator.comparing((String path) -> path.startsWith("knowledge/todos/")).thenComparing(path -> path))
                    .toList();
        }
    }

    private String original(ZipFile zip, Entry entry) throws IOException {
        var member = zip.getEntry(entry.path());
        if (member == null) throw new IOException("快照缺少原件：" + entry.path());
        try (var stream = zip.getInputStream(member)) {
            byte[] bytes = stream.readNBytes((int) KnowledgeStore.MAX_FILE_BYTES + 1);
            if (bytes.length > KnowledgeStore.MAX_FILE_BYTES || !digest(bytes).equals(entry.hash())) {
                throw new IOException("快照原件哈希不符：" + entry.path());
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String fileHash(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("源文件不存在或类型不支持");
        try (var in = Files.newInputStream(path)) { return digest(in); }
    }

    private static String archiveHash(ZipFile zip, String path) throws IOException {
        var entry = zip.getEntry(path);
        if (entry == null) throw new IOException("快照缺少原件：" + path);
        try (var in = zip.getInputStream(entry)) { return digest(in); }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String digest(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static String digest(InputStream source) throws IOException {
        MessageDigest hash = sha256();
        var input = new DigestInputStream(source, hash);
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int read; (read = input.read(buffer)) != -1; ) {
            total += read;
            if (total > 4L * 1024 * 1024 * 1024) throw new IOException("源文件超过归档限制");
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void requireSnapshotDatabase(ZipFile zip) throws IOException {
        var manifest = zip.getEntry("manifest.json");
        if (manifest == null || manifest.getSize() < 0 || manifest.getSize() > 64L * 1024 * 1024) {
            throw new IOException("迁移快照缺少有效的备份清单");
        }
        try (var in = zip.getInputStream(manifest)) {
            var expected = JSON.readTree(in).path(".kelly-data.db").asText();
            if (!expected.equals(archiveHash(zip, ".kelly-data.db"))) throw new IOException("正式库快照哈希不符");
        }
    }

    private static UUID uid(String kind, String path) {
        return UUID.nameUUIDFromBytes(("kelly:legacy:" + kind + ":" + path).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID systemUid(String path) {
        return UUID.nameUUIDFromBytes(("kelly:system:" + path).getBytes(StandardCharsets.UTF_8));
    }

    private Path planPath(UUID batch) { return files.safe(DIRECTORY + batch + "/plan.json"); }
    private Path archive(UUID batch) { return files.safe(archiveName(batch)); }
    private Path reportPath(UUID batch) { return files.safe(DIRECTORY + batch + "/report.json"); }
    private static String archiveName(UUID batch) { return DIRECTORY + batch + "/originals.zip"; }

    private Plan load(UUID batch) throws IOException {
        Plan plan = JSON.readValue(planPath(batch).toFile(), Plan.class);
        if (!batch.equals(plan.batch())) throw new IOException("批次标识不符");
        return plan;
    }

    private static void save(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".migration-", ".tmp");
        try {
            JSON.writeValue(temporary.toFile(), value);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String completion(List<Item> items) {
        return items.stream().allMatch(item -> item.state().equals("verified")) ? "complete" : "partial";
    }

    private static Report page(Plan plan, String state, List<Item> items, int offset) {
        if (offset < 0 || offset > items.size()) throw new IllegalArgumentException("分页偏移无效");
        int end = offset + Math.min(PAGE_SIZE, items.size() - offset);
        Map<String, Long> counts = new TreeMap<>();
        items.forEach(item -> counts.merge(item.state(), 1L, Long::sum));
        return new Report(plan.batch(), archiveName(plan.batch()), state, counts, items.subList(offset, end),
                end < items.size() ? end : null);
    }

    private static String message(Exception failure) {
        return Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
    }
}
