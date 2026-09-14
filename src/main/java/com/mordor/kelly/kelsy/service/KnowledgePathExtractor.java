/**
 * 知识路径提取器。
 *
 * <p>从 AI 回复文本中提取知识库文件路径引用。
 * 当 AI 助手在回复中引用了知识库文件（如 AGENTS.md、MEMORY.md、
 * knowledge/meetings/xxx.md 等），此类负责识别和提取这些路径。
 *
 * <p>支持的路径格式：
 * <ul>
 *   <li>{@code MEMORY.md} - 记忆索引文件</li>
 *   <li>{@code AGENTS.md} - AI 助手规范文件</li>
 *   <li>{@code memory/xxx.md} - 日记文件</li>
 *   <li>{@code knowledge/xxx.md} - 知识库文件</li>
 * </ul>
 *
 * <p>使用场景：
 * <ul>
 *   <li>在 {@link CitationTurn} 中用于跟踪 AI 引用了哪些知识文件</li>
 *   <li>在知识面板（KnowledgePane）中显示引用的来源文件</li>
 * </ul>
 *
 * <p>路径匹配使用正则表达式，支持 Unicode 字母、数字、点、斜杠等字符。
 */
package com.mordor.kelly.kelsy.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KnowledgePathExtractor {

    /**
     * 知识库路径匹配正则表达式。
     * 匹配 MEMORY.md、AGENTS.md、memory/xxx.md、knowledge/xxx.md 等路径。
     */
    private static final Pattern PATH = Pattern.compile(
            "(?:MEMORY\\.md|AGENTS\\.md|memory/[\\p{L}\\p{N}._/-]+\\.md|knowledge/[\\p{L}\\p{N}._/-]+\\.md)");

    /** 私有构造函数，防止实例化 */
    private KnowledgePathExtractor() {
    }

    /**
     * 提取文本中的第一个知识库路径。
     *
     * @param text AI 回复文本
     * @return 第一个匹配的路径，无匹配时返回 Optional.empty()
     */
    public static Optional<String> first(String text) {
        return all(text).stream().findFirst();
    }

    /**
     * 提取文本中的所有知识库路径（保持插入顺序，去重）。
     *
     * @param text AI 回复文本
     * @return 所有匹配的路径列表（LinkedHashSet 保持顺序去重）
     */
    public static List<String> all(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = PATH.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return List.copyOf(out);
    }
}
