/**
 * 待办事项扫描器。
 *
 * <p>扫描知识库中的待办卡片目录（knowledge/todos/），
 * 解析所有 Markdown 格式的待办文件，返回 {@link TodoCard} 列表。
 *
 * <p>扫描流程：
 * <ol>
 *   <li>定位 knowledge/todos/ 目录</li>
 *   <li>遍历所有 .md 文件</li>
 *   <li>使用 {@link TodoCardParser} 解析每个文件</li>
 *   <li>跳过无法解析的文件（记录警告日志）</li>
 *   <li>返回所有成功解析的待办卡片列表</li>
 * </ol>
 *
 * <p>使用场景：
 * <ul>
 *   <li>待办提醒服务（{@link TodoReminderService}）扫描待办事项</li>
 *   <li>UI 显示待办列表</li>
 * </ul>
 *
 * @see TodoCard
 * @see TodoCardParser
 */
package com.mordor.kelly.kelsy.todo;

import com.mordor.kelly.common.Diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class TodoScanner {

    /** 私有构造函数，防止实例化 */
    private TodoScanner() {
    }

    /**
     * 扫描指定知识库中的所有待办卡片。
     *
     * @param knowledgeRoot 知识库根目录
     * @return 解析成功的待办卡片列表
     */
    public static List<TodoCard> list(Path knowledgeRoot) {
        if (knowledgeRoot == null) {
            return List.of();
        }
        Path dir = knowledgeRoot.resolve("knowledge/todos");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<TodoCard> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .forEach(p -> add(knowledgeRoot, p, out));
        } catch (IOException e) {
            Diagnostics.warn("todo", "无法扫描待办目录: %s", e.getMessage());
        }
        return List.copyOf(out);
    }

    /**
     * 解析单个待办文件并添加到结果列表。
     */
    private static void add(Path knowledgeRoot, Path file, List<TodoCard> out) {
        String rel = knowledgeRoot.relativize(file).toString().replace('\\', '/');
        try {
            Optional<TodoCard> card = TodoCardParser.parse(Files.readString(file), rel);
            if (card.isEmpty()) {
                Diagnostics.warn("todo", "跳过无法解析的待办: %s", rel);
                return;
            }
            out.add(card.get());
        } catch (IOException e) {
            Diagnostics.warn("todo", "读取待办失败 %s: %s", rel, e.getMessage());
        }
    }
}
