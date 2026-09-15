package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSeederTest {

    @TempDir
    Path dir;

    @Test
    void writesMissingFilesOnce() throws Exception {
        WorkspaceSeeder.seed(dir);
        assertTrue(Files.isRegularFile(dir.resolve("AGENTS.md")));
        assertTrue(Files.isRegularFile(dir.resolve("MEMORY.md")));
        assertTrue(Files.isRegularFile(dir.resolve("knowledge/KNOWLEDGE.md")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/people")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/projects")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/playbooks")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/inbox")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/meetings")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/decisions")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/todos")));
        assertTrue(Files.isRegularFile(dir.resolve("skills/kelsy-knowledge/SKILL.md")));
        assertTrue(Files.isRegularFile(dir.resolve("skills/kelsy-knowledge/references/examples.md")));
        Files.writeString(dir.resolve("AGENTS.md"), "keep-me");
        WorkspaceSeeder.seed(dir);
        assertEquals("keep-me", Files.readString(dir.resolve("AGENTS.md")));
    }

    @Test
    void overwritesProductSkillButKeepsAgents() throws Exception {
        WorkspaceSeeder.seed(dir);
        Path skill = dir.resolve("skills/kelsy-knowledge/SKILL.md");
        Path examples = dir.resolve("skills/kelsy-knowledge/references/examples.md");
        Files.writeString(skill, "old-skill");
        Files.writeString(examples, "old-examples");
        Files.writeString(dir.resolve("AGENTS.md"), "keep-me");
        WorkspaceSeeder.seed(dir);
        assertEquals("keep-me", Files.readString(dir.resolve("AGENTS.md")));
        assertFalse(Files.readString(skill).equals("old-skill"));
        assertFalse(Files.readString(examples).equals("old-examples"));
        assertTrue(Files.readString(skill).contains("kelsy-knowledge"));
        assertTrue(Files.readString(skill).contains("澄清"));
        assertTrue(Files.readString(skill).contains("knowledge/meetings"));
        assertTrue(Files.readString(skill).contains("knowledge/todos"));
        assertTrue(Files.readString(skill).contains("已检索候选"));
        assertTrue(Files.readString(skill).contains("knowledge_search"));
        assertTrue(Files.readString(skill).contains("状态：open"));
        assertTrue(Files.readString(skill).contains("- 原文："));
    }
}
