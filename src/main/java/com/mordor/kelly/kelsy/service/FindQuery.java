/**
 * 知识库搜索查询。
 *
 * <p>解析用户的搜索指令，提取日期范围和关键词。
 * 支持多种日期格式和中文时间表达式。
 *
 * <p>支持的日期格式：
 * <ul>
 *   <li>{@code 2026-03-15} - 精确日期</li>
 *   <li>{@code 2026-03} - 整月</li>
 *   <li>{@code 2026} - 整年</li>
 * </ul>
 *
 * <p>支持的中文时间表达式：
 * <ul>
 *   <li>{@code 今天} - 当天</li>
 *   <li>{@code 昨天} - 前一天</li>
 *   <li>{@code 上周} - 最近 7 天</li>
 *   <li>{@code 本月} - 当前月份</li>
 *   <li>{@code 上月} - 上个月</li>
 *   <li>{@code 半年前} - 6 个月前后各 1 个月</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>
 *   FindQuery.parse("会议 2026-03", today)  → 日期 2026-03-01~2026-03-31, 关键词 ["会议"]
 *   FindQuery.parse("今天 项目", today)     → 日期 今天, 关键词 ["项目"]
 *   FindQuery.parse("需求文档", today)      → 无日期限制, 关键词 ["需求", "文档"]
 * </pre>
 *
 * @see KnowledgeStore#search(FindQuery)
 */
package com.mordor.kelly.kelsy.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record FindQuery(LocalDate fromInclusive, LocalDate toInclusive, List<String> keywords) {

    /**
     * 解析搜索查询字符串。
     *
     * @param raw   原始搜索文本（如 "会议 2026-03"）
     * @param today 当前日期（用于相对时间计算）
     * @return 解析后的查询对象
     */
    public static FindQuery parse(String raw, LocalDate today) {
        List<String> keywords = new ArrayList<>();
        LocalDate from = null;
        LocalDate to = null;
        String source = raw == null ? "" : raw.strip();
        if (source.isEmpty()) {
            return new FindQuery(null, null, List.of());
        }
        for (String tok : source.split("\\s+")) {
            String t = tok.toLowerCase(Locale.ROOT);
            if (t.matches("\\d{4}-\\d{2}-\\d{2}")) {
                // 精确日期：2026-03-15
                LocalDate d = LocalDate.parse(t);
                from = d;
                to = d;
            } else if (t.matches("\\d{4}-\\d{2}")) {
                // 整月：2026-03
                YearMonth ym = YearMonth.parse(t);
                from = ym.atDay(1);
                to = ym.atEndOfMonth();
            } else if (t.matches("\\d{4}")) {
                // 整年：2026
                from = LocalDate.of(Integer.parseInt(t), 1, 1);
                to = LocalDate.of(Integer.parseInt(t), 12, 31);
            } else if (t.equals("今天")) {
                from = today;
                to = today;
            } else if (t.equals("昨天")) {
                from = today.minusDays(1);
                to = today.minusDays(1);
            } else if (t.equals("上周")) {
                from = today.minusDays(6);
                to = today;
            } else if (t.equals("本月")) {
                YearMonth ym = YearMonth.from(today);
                from = ym.atDay(1);
                to = ym.atEndOfMonth();
            } else if (t.equals("上月")) {
                YearMonth ym = YearMonth.from(today).minusMonths(1);
                from = ym.atDay(1);
                to = ym.atEndOfMonth();
            } else if (t.equals("半年前")) {
                YearMonth center = YearMonth.from(today).minusMonths(6);
                from = center.minusMonths(1).atDay(1);
                to = center.plusMonths(1).atEndOfMonth();
            } else if (!tok.isBlank()) {
                keywords.add(tok);
            }
        }
        return new FindQuery(from, to, List.copyOf(keywords));
    }

    /**
     * 判断日记文件是否在查询的日期范围内。
     * 日记文件名格式为 yyyy-MM-dd.md。
     *
     * @param fileName 文件名
     * @return true 如果文件在日期范围内（或无日期限制）
     */
    public boolean matchesDailyFile(String fileName) {
        if (fromInclusive == null) {
            return true;
        }
        if (!fileName.matches("\\d{4}-\\d{2}-\\d{2}\\.md")) {
            return false;
        }
        LocalDate d = LocalDate.parse(fileName.substring(0, 10));
        return !d.isBefore(fromInclusive) && !d.isAfter(toInclusive);
    }
}
