/**
 * 提醒格式化器。
 *
 * <p>负责将待办提醒信息编码为文本格式，以及从文本格式反解析为结构化数据。
 *
 * <p>编码格式（用于 UI 显示）：
 * <pre>
 *   还有 2 条待办待处理
 *   - 完成产品评审 | 截止 2026-03-20 | knowledge/todos/2026-03-20-产品评审.md
 *   - 提交测试报告 | 截止 2026-03-18 | 已逾期 | knowledge/todos/2026-03-18-测试报告.md
 * </pre>
 *
 * <p>反解析格式（用于从 AI 回复中提取提醒信息）：
 * 支持从上述格式反解析为 {@link ReminderItem} 列表。
 *
 * @see ReminderItem
 * @see TodoReminderService
 */
package com.mordor.kelly.kelsy.todo;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ReminderFormat {

    /** 私有构造函数，防止实例化 */
    private ReminderFormat() {
    }

    /**
     * 将待办卡片列表编码为提醒文本。
     *
     * @param cards 待办卡片列表
     * @param today 当前日期（用于判断是否逾期）
     * @return 格式化的提醒文本
     */
    public static String encode(List<TodoCard> cards, LocalDate today) {
        List<TodoCard> list = cards == null ? List.of() : cards;
        StringBuilder sb = new StringBuilder();
        sb.append("还有 ").append(list.size()).append(" 条待办待处理");
        for (TodoCard card : list) {
            sb.append('\n').append("- ").append(card.title())
                    .append(" | 截止 ").append(card.due());
            if (TodoReminderPolicy.overdue(card, today)) {
                sb.append(" | 已逾期");
            }
            sb.append(" | ").append(card.relativePath());
        }
        return sb.toString();
    }

    /**
     * 从文本格式反解析提醒信息。
     *
     * @param content 提醒文本
     * @return 解析后的提醒项列表，解析失败时返回 Optional.empty()
     */
    public static Optional<List<ReminderItem>> parse(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String[] lines = content.split("\\R", -1);
        if (lines.length == 0 || !lines[0].startsWith("还有 ") || !lines[0].contains("条待办待处理")) {
            return Optional.empty();
        }
        List<ReminderItem> items = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("- ")) {
                return Optional.empty();
            }
            Optional<ReminderItem> item = parseItem(line.substring(2).strip());
            if (item.isEmpty()) {
                return Optional.empty();
            }
            items.add(item.get());
        }
        return Optional.of(List.copyOf(items));
    }

    /**
     * 解析单个提醒项。
     * 格式：标题 | 截止 日期 | [已逾期] | 路径
     */
    private static Optional<ReminderItem> parseItem(String body) {
        String[] parts = body.split(" \\| ");
        if (parts.length < 3) {
            return Optional.empty();
        }
        String title = parts[0].strip();
        String duePart = parts[1].strip();
        if (!duePart.startsWith("截止 ")) {
            return Optional.empty();
        }
        LocalDate due;
        try {
            due = LocalDate.parse(duePart.substring("截止 ".length()).strip());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        boolean overdue = false;
        String path;
        if (parts.length == 4 && "已逾期".equals(parts[2].strip())) {
            overdue = true;
            path = parts[3].strip();
        } else if (parts.length == 3) {
            path = parts[2].strip();
        } else {
            return Optional.empty();
        }
        if (title.isBlank() || !path.startsWith("knowledge/todos/")) {
            return Optional.empty();
        }
        return Optional.of(new ReminderItem(title, due, overdue, path));
    }
}
