package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.kelsy.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownDocumentStoreConcurrencyTest {
    @TempDir Path root;

    @Test
    void separateStoresAcceptOnlyOneUpdateForTheSameHash() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int round = 0; round < 10; round++) {
                Path workspace = root.resolve("round-" + round);
                var article = new MarkdownDocumentStore(workspace).create(
                        "knowledge/articles/a.md", "Title", "article", List.of(), List.of(), null, "Original");
                var ready = new CountDownLatch(8);
                var start = new CountDownLatch(1);
                var results = new ArrayList<java.util.concurrent.Future<String>>();
                for (int writer = 0; writer < 8; writer++) {
                    String markdown = article.markdown().replace("Original", "Writer " + writer);
                    // Different spellings and instances must still share the document lock.
                    String path = writer % 2 == 0 ? article.path() : "knowledge/articles/./a.md";
                    results.add(workers.submit(() -> {
                        var store = new MarkdownDocumentStore(workspace.resolve("."));
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        try {
                            return store.update(path, article.hash(), markdown).markdown();
                        } catch (StorageException e) {
                            assertEquals(StorageException.Code.CONFLICT, e.code());
                            return null;
                        }
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                var saved = new ArrayList<String>();
                for (var result : results) {
                    String markdown = result.get(10, TimeUnit.SECONDS);
                    if (markdown != null) saved.add(markdown);
                }
                assertEquals(1, saved.size(), "Exactly one writer must succeed");
                assertEquals(saved.getFirst(), Files.readString(workspace.resolve(article.path())));
            }
        }
    }
}
