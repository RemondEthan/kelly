package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.kelsy.config.KelsyConfig;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceReadToolTest {
    @TempDir Path home;

    @Test
    void assistantToolsReadOnlyCurrentUsersWorkspaceEvenWhenLaunchedFromHome() throws Exception {
        Path workspace = home.resolve("workspace");
        Path root = Files.createDirectories(workspace.resolve("alice"));
        Path outside = Files.writeString(home.resolve("outside.txt"), "private");
        Path sibling = Files.createDirectories(workspace.resolve("bob"));
        Path siblingFile = Files.writeString(sibling.resolve("other.txt"), "private");
        Files.writeString(Files.createDirectories(root.resolve("knowledge")).resolve("note.md"), "标题\n正文");
        Files.writeString(Files.createDirectories(root.resolve("memory")).resolve("diary.md"), "日记");
        try (var service = createAssistant(workspace)) {
            Toolkit tools = toolkit(service);
            assertEquals("2: 正文", call(tools, "read_file",
                    Map.of("path", "knowledge/note.md", "offset", 1, "limit", 1)));
            assertEquals("1|日记", call(tools, "memory_get", Map.of("path", "memory/diary.md")));
            assertTrue(call(tools, "list_files", Map.of()).contains("[DIR] knowledge"));
            assertNull(tools.getTool("grep_files"));
            assertNull(tools.getTool("glob_files"));

            for (String tool : new String[]{"read_file", "list_files", "memory_get"}) {
                for (String path : new String[]{outside.toString(), siblingFile.toString(),
                        "../../outside.txt", "../bob/other.txt", ".kelly-data.db"}) {
                    assertTrue(call(tools, tool, Map.of("path", path)).contains("PATH_REJECTED"),
                            tool + " accepted " + path);
                }
            }
        }
    }

    @Test
    void linksToOutsideAreRejectedAndOmittedFromListings() throws Exception {
        Path workspace = home.resolve("workspace");
        Path root = Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(home.resolve("outside.txt"), "private");
        try {
            Files.createSymbolicLink(root.resolve("external"), home);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symbolic links unavailable: " + e.getClass().getSimpleName());
        }

        try (var service = createAssistant(workspace)) {
            Toolkit tools = toolkit(service);
            assertTrue(call(tools, "read_file", Map.of("path", "external/outside.txt")).contains("PATH_REJECTED"));
            assertTrue(call(tools, "memory_get", Map.of("path", "external/outside.txt")).contains("PATH_REJECTED"));
            assertTrue(call(tools, "list_files", Map.of("path", "external")).contains("PATH_REJECTED"));
            assertFalse(call(tools, "list_files", Map.of()).contains("external"));
        }
    }

    private LocalAssistantService createAssistant(Path workspace) {
        var model = new KelsyConfig.ModelSettings("minimax", "test-key", null, null);
        var config = new KelsyConfig(model, workspace.toString(), null, null, null);
        String workingDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", home.toString());
            return LocalAssistantService.create(config, "alice");
        } finally {
            System.setProperty("user.dir", workingDirectory);
        }
    }

    private static Toolkit toolkit(LocalAssistantService service) throws ReflectiveOperationException {
        var field = LocalAssistantService.class.getDeclaredField("agent");
        field.setAccessible(true);
        return ((HarnessAgent) field.get(service)).getToolkit();
    }

    private static String call(Toolkit toolkit, String tool, Map<String, Object> arguments) {
        var result = toolkit.getTool(tool).callAsync(ToolCallParam.builder().input(arguments).build()).block();
        return ((TextBlock) result.getOutput().getFirst()).getText();
    }
}
