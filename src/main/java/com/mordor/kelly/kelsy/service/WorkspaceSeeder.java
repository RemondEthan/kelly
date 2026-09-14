/**
 * 工作空间种子初始化器。
 *
 * <p>在首次运行或工作空间为空时，从 classpath 资源文件中复制模板文件
 * 到工作空间目录，建立知识库的基础结构。
 *
 * <p>模板文件来源：{@code /com/mordor/kelly/kelsy/workspace/} 目录下的资源文件。
 *
 * <p>初始化的文件和目录：
 * <ul>
 *   <li><b>AGENTS.md</b> - AI 助手行为规范（仅首次创建，不覆盖已有文件）</li>
 *   <li><b>MEMORY.md</b> - 记忆索引（仅首次创建）</li>
 *   <li><b>knowledge/KNOWLEDGE.md</b> - 知识索引（仅首次创建）</li>
 *   <li><b>knowledge/{people,projects,playbooks,inbox,meetings,decisions,todos}/</b> - 知识子目录</li>
 *   <li><b>skills/kelsy-knowledge/SKILL.md</b> - 知识库技能定义（每次启动都更新）</li>
 *   <li><b>skills/kelsy-knowledge/references/examples.md</b> - 技能参考示例（每次启动都更新）</li>
 * </ul>
 *
 * <p>写入策略：
 * <ul>
 *   <li>{@link #writeIfAbsent} - 仅在文件不存在时写入（保护用户修改）</li>
 *   <li>{@link #writeAlways} - 每次启动都覆盖写入（确保技能定义最新）</li>
 * </ul>
 *
 * <p>使用场景：
 * <ul>
 *   <li>KelsyRuntime 启动时调用</li>
 *   <li>LocalAssistantService 创建时调用</li>
 * </ul>
 */
package com.mordor.kelly.kelsy.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class WorkspaceSeeder {

    /** 资源文件前缀路径 */
    private static final String PREFIX = "/com/mordor/kelly/kelsy/workspace/";

    /** 私有构造函数，防止实例化 */
    private WorkspaceSeeder() {
    }

    /**
     * 播种工作空间：创建目录结构并复制模板文件。
     *
     * @param workspace 工作空间根目录
     * @throws UncheckedIOException 如果文件操作失败
     */
    public static void seed(Path workspace) {
        try {
            Files.createDirectories(workspace);
            // 核心文件（仅首次创建）
            writeIfAbsent(workspace.resolve("AGENTS.md"), read("AGENTS.md"));
            writeIfAbsent(workspace.resolve("MEMORY.md"), read("MEMORY.md"));
            // 知识库目录结构
            Path knowledge = workspace.resolve("knowledge");
            Files.createDirectories(knowledge);
            writeIfAbsent(knowledge.resolve("KNOWLEDGE.md"), read("KNOWLEDGE.md"));
            for (String folder : List.of(
                    "people", "projects", "playbooks", "inbox", "meetings", "decisions", "todos")) {
                Files.createDirectories(knowledge.resolve(folder));
            }
            // 技能定义文件（每次启动都更新）
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

    /**
     * 仅在文件不存在时写入（保护用户已有的修改）。
     */
    private static void writeIfAbsent(Path path, String content) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /**
     * 每次启动都覆盖写入（确保模板内容最新）。
     */
    private static void writeAlways(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /**
     * 从 classpath 读取资源文件内容。
     *
     * @param name 资源文件名（相对于 PREFIX 路径）
     * @return 文件内容
     * @throws IOException 如果资源文件不存在或读取失败
     */
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
