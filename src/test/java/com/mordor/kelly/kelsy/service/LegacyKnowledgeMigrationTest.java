package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.kelsy.todo.TodoRepository;
import com.mordor.kelly.kelsy.todo.TodoStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class LegacyKnowledgeMigrationTest {
    @TempDir Path root;

    @Test void archivesAndVerifiesEveryOriginalAcrossRetryAndRestart() throws Exception {
        String articlePath = "knowledge/meetings/review.md";
        String todoPath = "knowledge/todos/report.md";
        String article = "# 交付评审\r\n- 日期：2025-03-12\r\n\r\n许可证交付记录\r\n";
        String todo = "# 待办 · 提交报告\n- 状态：已完成\n- 来源：[交付评审](" + articlePath + ")\n\n保留正文。\n";
        put(articlePath, article);
        put(todoPath, todo);
        put(".todo-reminder-ledger", "2025-03-12 LOGIN\n");

        LegacyKnowledgeMigration.Report planned;
        try (var store = new KnowledgeStore(root)) {
            var migration = new LegacyKnowledgeMigration(store);
            planned = migration.plan();
            assertEquals(article, Files.readString(root.resolve(articlePath)));
            assertTrue(store.todos().legacyImport(todoPath).isEmpty());

            var report = migration.apply(planned.batch());
            assertEquals("complete", report.state(), report.toString());
            assertEquals(todo, Files.readString(root.resolve(todoPath)));
            assertTrue(Files.readString(root.resolve(articlePath)).endsWith(article));
            try (var zip = new ZipFile(root.resolve(report.archive()).toFile())) {
                for (String path : List.of(articlePath, todoPath, ".todo-reminder-ledger")) {
                    try (var stream = zip.getInputStream(zip.getEntry(path))) {
                        assertArrayEquals((path.equals(articlePath) ? article : Files.readString(root.resolve(path)))
                                .getBytes(StandardCharsets.UTF_8), stream.readAllBytes());
                    }
                }
            }
            assertEquals(1, store.search(new FindQuery(null, null, List.of("许可证"))).size());
            var imported = store.todos().get(store.todos().legacyImport(todoPath).orElseThrow().uid());
            assertEquals(TodoStatus.CLOSED, imported.status());
            assertNull(imported.completedAt());
            assertNull(imported.dueDate());
            assertEquals(articlePath, imported.sources().getFirst().lastKnownPath());
            assertEquals(LocalDate.of(2025, 3, 12), new MarkdownDocumentStore(root).read(articlePath, null).date());
            assertEquals(1, store.todos().list(new TodoRepository.Query(TodoStatus.CLOSED, null, null,
                    TodoRepository.DueMode.UNDATED, "报告", 10, null)).items().size());
            assertTrue(store.todos().list(TodoRepository.Query.open()).items().isEmpty());
        }

        try (var store = new KnowledgeStore(root)) {
            var migration = new LegacyKnowledgeMigration(store);
            assertEquals("complete", migration.apply(planned.batch()).state());
            assertEquals("complete", migration.verify(planned.batch(), 0).state());
            var mapping = store.todos().legacyImport(todoPath).orElseThrow();
            assertEquals(1, store.todos().get(mapping.uid()).version());
            Files.delete(root.resolve(planned.archive()));
            assertThrows(IOException.class, () -> migration.verify(planned.batch(), 0));
            assertThrows(IOException.class, () -> migration.apply(planned.batch()));
        }
    }

    @Test void stalePreviewPreventsWritesAndUnrecognizedFilesStayIntact() throws Exception {
        put("knowledge/articles/a.md", "# 旧标题\n正文");
        put("knowledge/todos/unknown.md", "# 待办 · 检查\n- 状态：未知");
        try (var store = new KnowledgeStore(root)) {
            var migration = new LegacyKnowledgeMigration(store);
            var stale = migration.plan();
            put("knowledge/articles/a.md", "# 用户改过的标题\n正文");
            assertThrows(IOException.class, () -> migration.apply(stale.batch()));
            assertEquals("# 用户改过的标题\n正文", Files.readString(root.resolve("knowledge/articles/a.md")));

            var current = migration.plan();
            var report = migration.apply(current.batch());
            assertEquals("partial", report.state());
            assertEquals(1L, report.counts().get("review"));
            assertEquals("# 待办 · 检查\n- 状态：未知", Files.readString(root.resolve("knowledge/todos/unknown.md")));
            assertTrue(store.todos().legacyImport("knowledge/todos/unknown.md").isEmpty());
        }
    }

    @Test void resumesIndexingAfterArticleWasSavedWithoutIndex() throws Exception {
        String path = "knowledge/articles/history.md";
        String original = "# 旧记录\n历史事实";
        put(path, original);
        java.util.UUID batch;
        try (var store = new KnowledgeStore(root, null)) {
            var migration = new LegacyKnowledgeMigration(store);
            batch = migration.plan().batch();
            assertEquals("partial", migration.apply(batch).state());
            assertTrue(Files.readString(root.resolve(path)).endsWith(original));
        }
        try (var store = new KnowledgeStore(root)) {
            var migration = new LegacyKnowledgeMigration(store);
            assertEquals("complete", migration.apply(batch).state());
            assertEquals("complete", migration.verify(batch, 0).state());
            assertEquals(1, store.search(new FindQuery(null, null, List.of("历史事实"))).size());
        }
    }

    private void put(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
