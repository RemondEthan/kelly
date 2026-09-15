package com.mordor.kelly.kelsy.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class KnowledgeIndex implements AutoCloseable {

    private static final String SCHEMA_VERSION = "1";

    private final Path workspace;
    private final Connection conn;

    private KnowledgeIndex(Path workspace, Connection conn) {
        this.workspace = workspace;
        this.conn = conn;
    }

    public static KnowledgeIndex open(Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        Path db = root.resolve(".kelly-index.db");
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
            }
            KnowledgeIndex index = new KnowledgeIndex(root, conn);
            index.ensureSchema();
            return index;
        } catch (ClassNotFoundException | SQLException e) {
            throw new UncheckedIOException(new IOException("failed to open knowledge index", e));
        }
    }

    public void reconcile() {
        try {
            Set<String> disk = collectDiskPaths();
            Set<String> indexed = indexedPaths();
            for (String path : indexed) {
                if (!disk.contains(path)) {
                    delete(path);
                }
            }
            for (String path : disk) {
                Long stored = indexedMtime(path);
                long mtime = Files.getLastModifiedTime(resolve(path)).toMillis();
                if (stored == null || stored != mtime) {
                    upsert(path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    public void upsert(String relativePath) {
        Path path = resolve(relativePath);
        if (path == null) {
            return;
        }
        if (!Files.isRegularFile(path)) {
            delete(relativePath);
            return;
        }
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            String markdown = "";
            String body = "";
            if (size <= KnowledgeStore.MAX_FILE_BYTES) {
                markdown = Files.readString(path);
                body = markdown;
            }
            CardFields fields = CardFields.parse(relativePath, markdown);
            try (PreparedStatement meta = conn.prepareStatement(
                    """
                    INSERT OR REPLACE INTO cards_meta(path, mtime, type, date, who, status)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """);
                 PreparedStatement del = conn.prepareStatement("DELETE FROM cards_fts WHERE path=?");
                 PreparedStatement fts = conn.prepareStatement(
                         """
                         INSERT INTO cards_fts(path, title, aliases, body, type, who, date)
                         VALUES (?, ?, ?, ?, ?, ?, ?)
                         """)) {
                meta.setString(1, relativePath);
                meta.setLong(2, mtime);
                meta.setString(3, fields.type());
                meta.setString(4, fields.date());
                meta.setString(5, fields.who());
                meta.setString(6, fields.status());
                meta.executeUpdate();
                del.setString(1, relativePath);
                del.executeUpdate();
                fts.setString(1, relativePath);
                fts.setString(2, CjkNgrams.forIndex(fields.title()));
                fts.setString(3, CjkNgrams.forIndex(fields.aliases()));
                fts.setString(4, CjkNgrams.forIndex(body));
                fts.setString(5, CjkNgrams.forIndex(fields.type()));
                fts.setString(6, CjkNgrams.forIndex(fields.who()));
                fts.setString(7, CjkNgrams.forIndex(fields.date()));
                fts.executeUpdate();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    public void delete(String relativePath) {
        if (resolve(relativePath) == null) {
            return;
        }
        try (PreparedStatement meta = conn.prepareStatement("DELETE FROM cards_meta WHERE path=?");
             PreparedStatement fts = conn.prepareStatement("DELETE FROM cards_fts WHERE path=?")) {
            meta.setString(1, relativePath);
            meta.executeUpdate();
            fts.setString(1, relativePath);
            fts.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    public List<KnowledgeStore.Hit> search(FindQuery query, int limit) {
        int cap = Math.max(0, limit);
        if (query.keywords().isEmpty()) {
            if (query.fromInclusive() == null) {
                return List.of();
            }
            return searchByDate(query, cap);
        }
        String match = matchExpression(query.keywords());
        if (match.isBlank()) {
            return List.of();
        }
        // Date filtering happens in Java. When a window is set, do not SQL-LIMIT
        // before that filter — in-window hits can rank below out-of-window rows.
        boolean dateWindow = query.fromInclusive() != null;
        String sql = dateWindow
                ? """
                SELECT path, snippet(cards_fts, 3, '', '', '…', 20) AS snip, bm25(cards_fts) AS rank
                FROM cards_fts WHERE cards_fts MATCH ?
                ORDER BY rank
                """
                : """
                SELECT path, snippet(cards_fts, 3, '', '', '…', 20) AS snip, bm25(cards_fts) AS rank
                FROM cards_fts WHERE cards_fts MATCH ?
                ORDER BY rank LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, match);
            if (!dateWindow) {
                ps.setInt(2, cap);
            }
            List<KnowledgeStore.Hit> hits = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("path");
                    String date = metaDate(path);
                    if (!passesDate(path, date, query)) {
                        continue;
                    }
                    String snip = rs.getString("snip");
                    double bm25 = rs.getDouble("rank");
                    double score = Math.max(0, 1.0 - bm25 / 20.0);
                    hits.add(new KnowledgeStore.Hit(path, 1, snip == null ? "" : snip, score));
                    if (hits.size() >= cap) {
                        break;
                    }
                }
            }
            return List.copyOf(hits);
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    private void ensureSchema() throws SQLException {
        if (SCHEMA_VERSION.equals(readSchemaVersion())) {
            return;
        }
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("DROP TABLE IF EXISTS cards_fts");
            s.executeUpdate("DROP TABLE IF EXISTS cards_meta");
            s.executeUpdate("DROP TABLE IF EXISTS meta");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS cards_meta (
                      path TEXT PRIMARY KEY,
                      mtime INTEGER NOT NULL,
                      type TEXT, date TEXT, who TEXT, status TEXT
                    )
                    """);
            s.executeUpdate(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS cards_fts USING fts5(
                      path, title, aliases, body, type, who, date,
                      tokenize = 'unicode61'
                    )
                    """);
            s.executeUpdate("INSERT INTO meta(key, value) VALUES ('schema_version', '" + SCHEMA_VERSION + "')");
        }
    }

    private String readSchemaVersion() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private Set<String> collectDiskPaths() throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        Path memoryMd = workspace.resolve("MEMORY.md");
        if (Files.isRegularFile(memoryMd)) {
            paths.add("MEMORY.md");
        }
        Path memoryDir = workspace.resolve("memory");
        if (Files.isDirectory(memoryDir)) {
            try (var stream = Files.list(memoryDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .forEach(p -> paths.add(rel(p)));
            }
        }
        Path knowledge = workspace.resolve("knowledge");
        if (Files.isDirectory(knowledge)) {
            try (var stream = Files.walk(knowledge)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .forEach(p -> paths.add(rel(p)));
            }
        }
        return paths;
    }

    private Set<String> indexedPaths() throws SQLException {
        Set<String> paths = new LinkedHashSet<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT path FROM cards_meta")) {
            while (rs.next()) {
                paths.add(rs.getString(1));
            }
        }
        return paths;
    }

    private Long indexedMtime(String relativePath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT mtime FROM cards_meta WHERE path=?")) {
            ps.setString(1, relativePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private List<KnowledgeStore.Hit> searchByDate(FindQuery query, int limit) {
        List<KnowledgeStore.Hit> hits = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT path, date FROM cards_meta")) {
            while (rs.next()) {
                String path = rs.getString("path");
                String date = rs.getString("date");
                if (!passesDateOnly(path, date, query)) {
                    continue;
                }
                hits.add(new KnowledgeStore.Hit(path, 1, "", 0.5));
                if (hits.size() >= limit) {
                    break;
                }
            }
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
        return List.copyOf(hits);
    }

    private String metaDate(String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT date FROM cards_meta WHERE path=?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String matchExpression(List<String> keywords) {
        List<String> groups = new ArrayList<>();
        for (String keyword : keywords) {
            String ngrams = CjkNgrams.forQuery(keyword);
            if (ngrams.isBlank()) {
                continue;
            }
            String inner = String.join(" OR ", ngrams.split("\\s+"));
            groups.add("(" + inner + ")");
        }
        return String.join(" AND ", groups);
    }

    private static boolean passesDate(String path, String metaDate, FindQuery query) {
        if (query.fromInclusive() == null) {
            return true;
        }
        String name = fileName(path);
        if (name.matches("\\d{4}-\\d{2}-\\d{2}\\.md")) {
            return query.matchesDailyFile(name);
        }
        if (metaDate != null && !metaDate.isBlank()) {
            return dateInRange(metaDate, query);
        }
        return true;
    }

    private static boolean passesDateOnly(String path, String metaDate, FindQuery query) {
        String name = fileName(path);
        if (query.matchesDailyFile(name)) {
            return true;
        }
        return metaDate != null && !metaDate.isBlank() && dateInRange(metaDate, query);
    }

    private static boolean dateInRange(String raw, FindQuery query) {
        LocalDate date = parseDate(raw);
        if (date == null) {
            return false;
        }
        return !date.isBefore(query.fromInclusive()) && !date.isAfter(query.toInclusive());
    }

    private static LocalDate parseDate(String raw) {
        String s = raw.strip();
        if (s.length() >= 10 && s.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        String norm = relativePath.replace('\\', '/');
        if (norm.contains("..") || norm.startsWith("/") || norm.contains(":")) {
            return null;
        }
        Path resolved = workspace.resolve(norm).normalize();
        if (!resolved.startsWith(workspace)) {
            return null;
        }
        return resolved;
    }

    private String rel(Path path) {
        return workspace.relativize(path).toString().replace('\\', '/');
    }

    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }
}
