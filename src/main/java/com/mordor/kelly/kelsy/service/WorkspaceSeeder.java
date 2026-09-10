package com.mordor.kelly.kelsy.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class WorkspaceSeeder {

    private static final String PREFIX = "/com/mordor/kelly/kelsy/workspace/";

    private WorkspaceSeeder() {
    }

    public static void seed(Path workspace) {
        try {
            Files.createDirectories(workspace);
            writeIfAbsent(workspace.resolve("AGENTS.md"), read("AGENTS.md"));
            writeIfAbsent(workspace.resolve("MEMORY.md"), read("MEMORY.md"));
            Path knowledge = workspace.resolve("knowledge");
            Files.createDirectories(knowledge);
            writeIfAbsent(knowledge.resolve("KNOWLEDGE.md"), read("KNOWLEDGE.md"));
            for (String folder : List.of(
                    "people", "projects", "playbooks", "inbox", "meetings", "decisions", "todos")) {
                Files.createDirectories(knowledge.resolve(folder));
            }
            writeAlways(
                    workspace.resolve("skills/kelsy-knowledge/SKILL.md"),
                    read("skills/kelsy-knowledge/SKILL.md"));
            writeAlways(
                    workspace.resolve("skills/kelsy-knowledge/references/examples.md"),
                    read("skills/kelsy-knowledge/references/examples.md"));
        } catch (IOException e) {
            throw new UncheckedIOException("无法初始化知识库：" + workspace, e);
        }
    }

    private static void writeIfAbsent(Path path, String content) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void writeAlways(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String read(String name) throws IOException {
        String resource = PREFIX + name;
        try (InputStream in = WorkspaceSeeder.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("缺少种子资源：" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
