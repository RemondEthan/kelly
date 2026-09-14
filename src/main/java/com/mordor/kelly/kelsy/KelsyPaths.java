/**
 * Kelsy 文件系统路径配置。
 *
 * <p>定义了 Kelsy 子系统在本地磁盘上的三个关键路径：
 * <ul>
 *   <li><b>config</b> - 配置文件路径（~/.kelly/kelsy/config.json）：存储 API Key、模型选择等配置</li>
 *   <li><b>workspace</b> - 工作空间目录（~/.kelly/kelsy/workspace）：存储知识库、记忆、技能等文件</li>
 *   <li><b>legacyConfig</b> - 旧版配置路径（~/.kelsy/config.json）：用于迁移旧版配置</li>
 * </ul>
 *
 * <p>采用 Java record 类型，不可变且自动生成 equals/hashCode/toString。
 *
 * <p>路径约定：
 * <ul>
 *   <li>所有路径基于用户主目录 {@code System.getProperty("user.home")}</li>
 *   <li>支持 {@code ~} 前缀的路径解析（在 {@link KelsyConfig#workspacePath()} 中处理）</li>
 *   <li>工作空间路径可通过 {@link #withWorkspace(Path)} 覆盖（用于自定义配置）</li>
 * </ul>
 */
package com.mordor.kelly.kelsy;

import java.nio.file.Path;

public record KelsyPaths(Path config, Path workspace, Path legacyConfig) {

    /**
     * 创建默认的路径配置（基于当前用户的主目录）。
     *
     * @return 默认路径配置
     */
    public static KelsyPaths defaults() {
        return forHome(Path.of(System.getProperty("user.home")));
    }

    /**
     * 根据指定的主目录创建路径配置。
     *
     * <p>目录结构：
     * <pre>
     *   ~/.kelly/kelsy/
     *   ├── config.json          ← 配置文件
     *   └── workspace/           ← 工作空间
     *       ├── AGENTS.md        ← AI 助手行为规范
     *       ├── MEMORY.md        ← 记忆索引
     *       ├── knowledge/       ← 知识库
     *       │   ├── meetings/    ← 会议卡片
     *       │   ├── decisions/   ← 决策卡片
     *       │   ├── todos/       ← 待办卡片
     *       │   └── ...
     *       └── memory/          ← 日记目录
     * </pre>
     *
     * @param home 用户主目录路径
     * @return 该用户的路径配置
     */
    public static KelsyPaths forHome(Path home) {
        return new KelsyPaths(
                home.resolve(".kelly").resolve("kelsy").resolve("config.json"),
                home.resolve(".kelly").resolve("kelsy").resolve("workspace"),
                home.resolve(".kelsy").resolve("config.json"));
    }

    /**
     * 创建带有自定义工作空间路径的新路径配置。
     * 配置文件和旧版配置路径保持不变。
     *
     * @param workspace 自定义的工作空间目录路径
     * @return 新的路径配置
     */
    public KelsyPaths withWorkspace(Path workspace) {
        return new KelsyPaths(config, workspace, legacyConfig);
    }
}
