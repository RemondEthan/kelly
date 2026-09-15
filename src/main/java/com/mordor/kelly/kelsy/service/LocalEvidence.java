/**
 * 本地证据提取器。
 *
 * <p>从用户输入中提取知识库搜索关键词，用于在本地知识库中
 * 查找相关的历史记录、会议纪要、决策文档等。
 *
 * <p>功能：
 * <ul>
 *   <li>识别用户消息中的语义关键词（待办、会议、决定等）</li>
 *   <li>从用户查询中提取搜索关键词（去除停用词和标点）</li>
 *   <li>为知识库搜索提供查询条件</li>
 * </ul>
 *
 * <p>停用词列表：过滤掉"最近有什么"、"当时怎么定的"等常见无意义短语，
 * 保留核心关键词用于搜索。
 *
 * <p>使用场景：
 * 用户问"最近有什么会议"时，提取关键词 ["会议"]，
 * 然后在知识库的 meetings/ 目录下搜索相关卡片。
 *
 * @see KnowledgeStore#search(FindQuery)
 * @see FindQuery
 */
package com.mordor.kelly.kelsy.service;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

public final class LocalEvidence {

    /** 停用词列表：这些短语在搜索时不提供有效信息，应被过滤 */
    private static final List<String> STOPS = List.of(
            "最近有什么", "当时怎么定的", "怎么定的", "是谁", "请问", "一下",
            "我最近", "有什么", "当时", "最近", "我");

    /** 回忆线索：相对时间、归档类型，或 ISO 年月 */
    private static final Pattern RECALL = Pattern.compile(
            "当时|会议|纪要|决定|待办|昨天|上周|上月|本月|半年前|去年|\\d{4}-\\d{2}");

    /** 私有构造函数，防止实例化 */
    private LocalEvidence() {
    }

    /**
     * 判断文本是否提及待办事项。
     *
     * @param text 用户输入文本
     * @return true 如果包含"待办"关键词
     */
    public static boolean mentionsTodos(String text) {
        return text != null && text.contains("待办");
    }

    /**
     * 判断文本是否提及会议。
     *
     * @param text 用户输入文本
     * @return true 如果包含"会议"或"纪要"
     */
    public static boolean mentionsMeetings(String text) {
        return text != null && (text.contains("会议") || text.contains("纪要"));
    }

    /**
     * 判断文本是否提及决策。
     *
     * @param text 用户输入文本
     * @return true 如果包含"决定"
     */
    public static boolean mentionsDecisions(String text) {
        return text != null && text.contains("决定");
    }

    /**
     * 判断文本是否带回忆线索（更宽松的检索阈值）。
     *
     * @param text 用户输入文本
     * @return true 如果包含回忆关键词或 {@code yyyy-MM} 日期
     */
    public static boolean looksLikeRecall(String text) {
        return text != null && RECALL.matcher(text).find();
    }

    /**
     * 从用户查询中提取搜索关键词。
     *
     * <p>处理流程：
     * <ol>
     *   <li>去除标点符号（？？！。，等）</li>
     *   <li>去除停用词（"最近有什么"、"请问"等）</li>
     *   <li>使用 FindQuery 解析剩余文本</li>
     *   <li>过滤掉长度小于 2 的关键词</li>
     * </ol>
     *
     * @param outgoing 用户查询文本
     * @return 关键词列表（长度 >= 2）
     */
    public static List<String> terms(String outgoing) {
        if (outgoing == null || outgoing.isBlank()) {
            return List.of();
        }
        String q = outgoing;
        // 去除标点符号
        for (String mark : List.of("？", "?", "！", "!", "。", "，", ",")) {
            q = q.replace(mark, " ");
        }
        // 去除停用词
        for (String stop : STOPS) {
            q = q.replace(stop, " ");
        }
        return FindQuery.parse(q, LocalDate.now()).keywords().stream()
                .filter(k -> k.length() >= 2)
                .filter(k -> !k.equals("你好"))
                .toList();
    }
}
