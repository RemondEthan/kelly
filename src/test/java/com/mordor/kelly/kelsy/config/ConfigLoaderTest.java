package com.mordor.kelly.kelsy.config;

import com.mordor.kelly.kelsy.KelsyPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir Path tmp;

    @Test
    void copiesLegacyThenHasKey() throws Exception {
        Path home = tmp.resolve("home");
        KelsyPaths paths = KelsyPaths.forHome(home);
        Files.createDirectories(paths.legacyConfig().getParent());
        Files.writeString(paths.legacyConfig(), """
                {"model":{"apiKey":"sk-test","baseUrl":"https://api.minimaxi.com/v1","modelName":"MiniMax-M3"}}
                """);
        assertTrue(ConfigLoader.ensureAndHasApiKey(paths));
        assertTrue(Files.exists(paths.config()));
        assertTrue(Files.readString(paths.config()).contains("sk-test"));
    }

    @Test
    void writesEmptyTemplateWhenNoLegacy() throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp.resolve("empty"));
        assertFalse(ConfigLoader.ensureAndHasApiKey(paths));
        assertTrue(Files.exists(paths.config()));
        assertTrue(Files.readString(paths.config()).contains("\"apiKey\": \"\"")
                || Files.readString(paths.config()).contains("\"apiKey\":\"\""));
    }

    @Test
    void saveWorkspaceKeepsApiKey() throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp.resolve("cfg"));
        Files.createDirectories(paths.config().getParent());
        Files.writeString(paths.config(), """
                {"model":{"apiKey":"sk-keep","baseUrl":"https://x","modelName":"M"},"workspaceDir":"old"}
                """);
        KelsyConfig current = ConfigLoader.peek(paths);
        ConfigLoader.save(paths, new KelsyConfig(
                current.model(), "/tmp/custom-ws", "ada", current.selfAvatarPath(), current.kelsyAvatarPath()));
        KelsyConfig again = ConfigLoader.peek(paths);
        assertEquals("sk-keep", again.model().apiKey());
        assertEquals("/tmp/custom-ws", again.workspaceDir());
        assertEquals("ada", again.lastUsername());
    }
}
