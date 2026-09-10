package com.mordor.kelly.kelsy.todo;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ReminderFormat {

    private ReminderFormat() {
    }

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
