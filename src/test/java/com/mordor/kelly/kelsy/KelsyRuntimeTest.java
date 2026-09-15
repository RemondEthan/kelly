package com.mordor.kelly.kelsy;

import com.mordor.kelly.kelsy.service.AssistantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KelsyRuntimeTest {

    @TempDir Path tmp;

    @Test
    void seedsStoreAndReusesAssistant() throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp);
        AtomicInteger builds = new AtomicInteger();
        AssistantService fake = new AssistantService() {
            @Override public void chat(String text, ReplyHandler handler) {}
            @Override public void close() {}
        };
        KelsyRuntime runtime = KelsyRuntime.open(paths, "alice", p -> {
            builds.incrementAndGet();
            return fake;
        });
        assertFalse(runtime.hasApiKey());
        assertNotNull(runtime.store("alice"));
        assertSame(runtime.store("alice"), runtime.store("alice"));
        assertTrue(Files.isDirectory(paths.workspace()));
        assertEquals(null, runtime.ensureAssistant());
        assertEquals(0, builds.get());

        Files.writeString(paths.config(), "{\"model\":{\"apiKey\":\"sk-test\"}}");
        assertTrue(runtime.hasApiKey());
        assertSame(fake, runtime.ensureAssistant());
        runtime.ensureAssistant();
        assertEquals(1, builds.get());
        runtime.close();
    }

    @Test
    void openUsesWorkspaceFromConfig() throws Exception {
        KelsyPaths home = KelsyPaths.forHome(tmp);
        Files.createDirectories(home.config().getParent());
        Path custom = tmp.resolve("custom-ws");
        Files.writeString(home.config(),
                "{\"model\":{\"apiKey\":\"\"},\"workspaceDir\":\""
                        + custom.toAbsolutePath() + "\"}");
        KelsyPaths resolved = KelsyRuntime.resolve(home);
        assertEquals(custom.toAbsolutePath().normalize(),
                resolved.workspace().toAbsolutePath().normalize());
        assertEquals(home.config(), resolved.config());
        KelsyRuntime runtime = KelsyRuntime.open(resolved, "alice", p -> null);
        assertTrue(Files.isDirectory(custom));
        runtime.close();
    }
}
