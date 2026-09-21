package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeStoreUserRootTest {

    @TempDir
    Path dir;

    @Test
    void forUserReadsNamespacedMemory() throws Exception {
        Files.createDirectories(dir.resolve("remond/memory"));
        Files.writeString(dir.resolve("remond/MEMORY.md"), "- 用户索引");
        Files.writeString(dir.resolve("remond/memory/2026-09-02.md"), "- 今天下午讨论");
        Files.writeString(dir.resolve("MEMORY.md"), "- 根上种子");

        KnowledgeStore store = KnowledgeStore.forUser(dir, "remond");
        var memory = store.read("MEMORY.md");
        assertInstanceOf(KnowledgeStore.Read.Ok.class, memory);
        assertTrue(((KnowledgeStore.Read.Ok) memory).markdown().contains("用户索引"));
        assertTrue(store.list().stream().anyMatch(e ->
                e.children().stream().anyMatch(c -> "memory/2026-09-02.md".equals(c.relativePath()))));
    }

    @Test
    void knowledgeRootJoinsUsername() {
        assertEquals(dir.resolve("remond").toAbsolutePath().normalize(),
                KnowledgeStore.knowledgeRoot(dir, "remond"));
    }

    @Test
    void knowledgeRootRejectsPathSeparators() {
        // 这些都是曾经/可能把知识根嵌成多层子目录的违规用户名，必须直接报错而不是静默嵌套
        for (String bad : new String[]{
                "ksw/ksw",      // 真实事故：双嵌
                "ksw\\ksw",     // Windows 风格分隔符
                "ksw:sub",      // Windows 盘符冒号
                "ksw\u0000x",   // NUL 控制字符
                "/abs/path",    // 绝对路径
                "a/b"           // 普通正斜杠
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> KnowledgeStore.knowledgeRoot(dir, bad),
                    "应当拒绝 username: " + bad);
        }
    }

    @Test
    void knowledgeRootBlankUsernameReturnsBase() {
        assertEquals(dir.toAbsolutePath().normalize(),
                KnowledgeStore.knowledgeRoot(dir, ""));
        assertEquals(dir.toAbsolutePath().normalize(),
                KnowledgeStore.knowledgeRoot(dir, null));
        assertEquals(dir.toAbsolutePath().normalize(),
                KnowledgeStore.knowledgeRoot(dir, "   "));
    }

    /**
     * 模拟 09-21 之前的真实事故：用户今早写的会议纪要困在
     * {@code <userRoot>/<username>/knowledge/meetings/} 下。新代码读取
     * {@code <userRoot>/knowledge/meetings/}（空目录），所以面板一直显示
     * 「文件不存在」。{@link KnowledgeStore#migrateLegacyDoubleNested(Path, String)}
     * 必须在启动时把内层目录合并到外层。
     */
    @Test
    void migrateFoldsLegacyDoubleNestedIntoUserRoot() throws Exception {
        // 启动后已种子化的新用户根目录是 <dir>/<user>/；旧内容却错位进了 <dir>/<user>/<user>/。
        Path userRoot = dir.resolve("ksw");
        Files.createDirectories(userRoot.resolve("knowledge"));

        Path legacy = userRoot.resolve("ksw");
        Files.createDirectories(legacy.resolve("knowledge/meetings"));
        Files.writeString(legacy.resolve("MEMORY.md"), "旧索引：含今早的会议要点");
        Files.writeString(legacy.resolve("knowledge/meetings/2026-09-21-红叶-集成讨论.md"),
                "# 2026-09-21 与红叶集成讨论会议\n");

        boolean moved = KnowledgeStore.migrateLegacyDoubleNested(userRoot, "ksw");

        assertTrue(moved, "应当报告迁移了至少一个条目");
        assertTrue(Files.exists(userRoot.resolve("MEMORY.md")), "旧 MEMORY.md 应上移一层");
        assertTrue(Files.exists(userRoot.resolve(
                "knowledge/meetings/2026-09-21-红叶-集成讨论.md")), "会议纪要应上移一层");
        assertFalse(Files.exists(legacy), "内层遗留目录应当被清掉");

        // 新代码路径上现在能直接读到这条卡片
        KnowledgeStore store = KnowledgeStore.forUser(dir, "ksw");
        var read = store.read("knowledge/meetings/2026-09-21-红叶-集成讨论.md");
        assertInstanceOf(KnowledgeStore.Read.Ok.class, read);
        assertTrue(((KnowledgeStore.Read.Ok) read).markdown().contains("红叶集成讨论"));
    }

    @Test
    void migrateMergesConflictingDirectories() throws Exception {
        Path userRoot = dir.resolve("u");
        Files.createDirectories(userRoot.resolve("knowledge/meetings"));
        Files.writeString(userRoot.resolve("knowledge/meetings/from-new.md"), "new");
        Files.createDirectories(userRoot.resolve("u/knowledge/meetings"));
        Files.writeString(userRoot.resolve("u/knowledge/meetings/from-legacy.md"), "legacy");

        KnowledgeStore.migrateLegacyDoubleNested(userRoot, "u");

        assertTrue(Files.exists(userRoot.resolve("knowledge/meetings/from-new.md")));
        assertTrue(Files.exists(userRoot.resolve("knowledge/meetings/from-legacy.md")));
        assertFalse(Files.exists(userRoot.resolve("u")));
    }

    @Test
    void migrateRenamesLegacyFileOnContentConflict() throws Exception {
        Path userRoot = dir.resolve("u");
        Files.createDirectories(userRoot);
        Files.writeString(userRoot.resolve("MEMORY.md"), "新版空索引");
        Files.createDirectories(userRoot.resolve("u"));
        Files.writeString(userRoot.resolve("u/MEMORY.md"), "旧版有内容");

        KnowledgeStore.migrateLegacyDoubleNested(userRoot, "u");

        // 目标文件被新代码种子占位，应保留；旧版重命名以 .legacy 结尾并存。
        assertEquals("新版空索引", Files.readString(userRoot.resolve("MEMORY.md")));
        assertTrue(Files.exists(userRoot.resolve("MEMORY.md.legacy"))
                || Files.exists(userRoot.resolve("MEMORY.md.legacy0")));
        assertFalse(Files.exists(userRoot.resolve("u")));
    }

    /**
     * 真实事故：今早用户写的 MEMORY.md 索引（千字节级）困在内层；新代码刚种子化的
     * MEMORY.md 只有 62 字节。迁移应把种子覆盖掉，让用户的索引重新生效——否则
     * {@code upgradeKnowledge} 会读到空种子，把用户记忆当作空来 compact。
     */
    @Test
    void migrateOverwritesSeedTemplateWhenLegacyHasRealContent() throws Exception {
        Path userRoot = dir.resolve("ksw");
        Files.createDirectories(userRoot);
        Files.writeString(userRoot.resolve("MEMORY.md"), "# Memory\n\n（短索引。只留现在仍为真的条目。）\n");
        Files.createDirectories(userRoot.resolve("ksw"));
        // 真实索引远超种子大小（≥256 字节）；字节阈值触发「非种子」分支。
        String realIndex = "## 红叶计划\n- 友商红叶替代新成本，本次只接「计划成本」模块"
                + "\n- 卡片: knowledge/meetings/2026-09-21-红叶-系统集成方案讨论.md\n";
        Files.writeString(userRoot.resolve("ksw/MEMORY.md"), realIndex);

        KnowledgeStore.migrateLegacyDoubleNested(userRoot, "ksw");

        assertEquals(realIndex, Files.readString(userRoot.resolve("MEMORY.md")),
                "真实索引应当覆盖 WorkspaceSeeder 留下的空种子");
        assertFalse(Files.exists(userRoot.resolve("ksw")),
                "内层目录应在合并后清掉");
    }

    @Test
    void migrateRemovesEmptyLegacyDirectory() throws Exception {
        Path userRoot = dir.resolve("u");
        Files.createDirectories(userRoot);
        Files.createDirectories(userRoot.resolve("u"));

        boolean moved = KnowledgeStore.migrateLegacyDoubleNested(userRoot, "u");

        assertFalse(moved, "空目录不应算作迁移条目");
        assertFalse(Files.exists(userRoot.resolve("u")), "空内层目录应当被清掉");
    }

    @Test
    void migrateIsNoOpWhenLegacyMissing() throws Exception {
        Path userRoot = dir.resolve("u");
        Files.createDirectories(userRoot);

        boolean moved = KnowledgeStore.migrateLegacyDoubleNested(userRoot, "u");

        assertFalse(moved);
    }

    @Test
    void migrateRejectsPathSeparatorInUsername() {
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeStore.migrateLegacyDoubleNested(dir, "ksw/ksw"));
    }

    /**
     * {@link KnowledgeStore#bootstrap(Path, String)} 是 KelsyRuntime.open() 和
     * LocalAssistantService.create(...) 都要走的入口，必须同时把种子写入和
     * 历史双层嵌套迁移做完。这条测试模拟「09-21 旧事故后第一次冷启动」：外层
     * 没有种子文件，内层有用户真实内容，验证 bootstrap 一次调用就清理完。
     */
    @Test
    void bootstrapSeedsAndMigratesInOneCall() throws Exception {
        // 用户名 ksw，内层遗留 <dir>/ksw/ksw/knowledge/meetings/...
        Path userRoot = dir.resolve("ksw");
        Files.createDirectories(userRoot.resolve("ksw/knowledge/meetings"));
        Files.writeString(userRoot.resolve("ksw/MEMORY.md"), "## 用户的真实索引");
        Files.writeString(userRoot.resolve("ksw/knowledge/meetings/2026-09-21-红叶-集成讨论.md"),
                "# 2026-09-21 与红叶集成讨论会议\n");

        Path resolved2 = KnowledgeStore.bootstrap(dir, "ksw");

        // 1. 返回的就是 <dir>/ksw
        assertEquals(userRoot.toAbsolutePath().normalize(), resolved2);

        // 2. 种子已写入（<root>/MEMORY.md 存在）
        assertTrue(Files.isRegularFile(userRoot.resolve("MEMORY.md")));

        // 3. 嵌套层已被合并：用户的真实 MEMORY.md 应被提升到外层，覆盖种子
        assertEquals("## 用户的真实索引", Files.readString(userRoot.resolve("MEMORY.md")));
        assertTrue(Files.exists(userRoot.resolve("knowledge/meetings/2026-09-21-红叶-集成讨论.md")));

        // 4. 内层目录应被清掉
        assertFalse(Files.exists(userRoot.resolve("ksw")));
    }

    @Test
    void bootstrapIdempotentWhenNothingToMigrate() throws Exception {
        // 干净启动：外层为空，内层不存在
        Path userRoot = KnowledgeStore.bootstrap(dir, "ksw");

        assertEquals(dir.resolve("ksw").toAbsolutePath().normalize(), userRoot);
        assertTrue(Files.isRegularFile(userRoot.resolve("MEMORY.md")));

        // 第二次跑应当 no-op：MEMORY.md 不会被覆盖
        Files.writeString(userRoot.resolve("MEMORY.md"), "用户笔记：不可覆盖");
        KnowledgeStore.bootstrap(dir, "ksw");
        assertEquals("用户笔记：不可覆盖", Files.readString(userRoot.resolve("MEMORY.md")));
    }

    @Test
    void bootstrapRejectsPathSeparatorInUsername() {
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeStore.bootstrap(dir, "ksw/ksw"));
    }
}
