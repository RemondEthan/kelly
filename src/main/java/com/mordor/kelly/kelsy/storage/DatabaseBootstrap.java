package com.mordor.kelly.kelsy.storage;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Creates fresh databases transactionally. Never deletes an existing data database. */
public final class DatabaseBootstrap {
    private DatabaseBootstrap() {}
    public static Connection open(Path root, boolean index) {
        Connection c = null;
        try {
            Files.createDirectories(root);
            Class.forName("org.sqlite.JDBC");
            Path path = root.resolve(index ? ".kelly-index.db" : ".kelly-data.db");
            if (Files.isSymbolicLink(path)) throw new StorageException(StorageException.Code.PATH_REJECTED, "Database cannot be a symlink");
            c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=" + (index ? "NORMAL" : "FULL"));
                int version;
                try (ResultSet r = s.executeQuery("PRAGMA user_version")) { version = r.getInt(1); }
                if (version > 1) throw new StorageException(StorageException.Code.UNSUPPORTED_SCHEMA, "Database version " + version + " is newer than this application");
                if (version == 0) {
                    try (ResultSet r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                        if (r.getInt(1) != 0) throw new StorageException(StorageException.Code.UNSUPPORTED_SCHEMA, "Unversioned database requires explicit recovery");
                    }
                    String resource = "/com/mordor/kelly/kelsy/storage/schema/" + (index ? "index-v1.sql" : "data-v1.sql");
                    try (var in = DatabaseBootstrap.class.getResourceAsStream(resource)) {
                        if (in == null) throw new IllegalStateException("Missing schema " + resource);
                        executeScript(c, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
                String[] required = index
                    ? new String[]{"documents", "chunks", "search_content", "article_fts", "search_content_ai", "search_content_ad", "search_content_au"}
                    : new String[]{"todos", "todo_sources", "todo_events", "reminder_slots", "reminder_batches", "todo_fts", "todo_search_content_ai", "todo_search_content_ad", "todo_search_content_au"};
                for (String name : required) {
                    try (var p = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE name=?")) {
                        p.setString(1,name);
                        try (var r = p.executeQuery()) {
                            if (!r.next()) throw new StorageException(StorageException.Code.UNSUPPORTED_SCHEMA, "Missing database object: " + name);
                        }
                    }
                }
            }
            return c;
        } catch (Exception e) {
            if (c != null) try { c.close(); } catch (SQLException ignored) {}
            if (e instanceof StorageException se) throw se;
            throw new StorageException(StorageException.Code.STORE_UNAVAILABLE, "Cannot open local database", e);
        }
    }
    static void executeScript(Connection c, String sql) throws SQLException {
        StringBuilder statement = new StringBuilder();
        boolean trigger = false;
        try (Statement s = c.createStatement()) {
            for (String line : sql.split("\\R")) {
                if (line.strip().startsWith("--") || line.isBlank()) continue;
                if (line.strip().startsWith("CREATE TRIGGER")) trigger = true;
                statement.append(line).append('\n');
                if (line.strip().endsWith(";") && (!trigger || line.strip().equals("END;"))) {
                    s.execute(statement.toString()); statement.setLength(0); trigger=false;
                }
            }
            if (!statement.toString().isBlank()) throw new SQLException("Incomplete schema statement");
        } catch (SQLException e) {
            try (Statement s = c.createStatement()) { s.execute("ROLLBACK"); } catch (SQLException ignored) {}
            throw e;
        }
    }
}
