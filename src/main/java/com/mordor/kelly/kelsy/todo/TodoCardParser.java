package com.mordor.kelly.kelsy.todo;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

public final class TodoCardParser {

    private TodoCardParser() {
    }

    public static Optional<TodoCard> parse(String markdown, String relativePath) {
        if (markdown == null || markdown.isBlank() || relativePath == null || relativePath.isBlank()) {
            return Optional.empty();
        }
        String dueRaw = null;
        String statusRaw = null;
        String titleRaw = null;
        String heading = null;
        for (String line : markdown.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("# ")) {
                heading = stripped.substring(2).strip();
                continue;
            }
            Field field = field(stripped);
            if (field == null) {
                continue;
            }
            switch (field.name()) {
                case "截止" -> dueRaw = field.value();
                case "状态" -> statusRaw = field.value();
                case "标题" -> titleRaw = field.value();
                default -> {
                }
            }
        }
        LocalDate due;
        try {
            due = dueRaw == null ? null : LocalDate.parse(dueDate(dueRaw));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        if (due == null) {
            return Optional.empty();
        }
        TodoStatus status = parseStatus(statusRaw);
        if (status == null) {
            return Optional.empty();
        }
        String title = titleOf(titleRaw, heading, relativePath);
        return Optional.of(new TodoCard(title, due, status, relativePath.replace('\\', '/')));
    }

    private static String dueDate(String raw) {
        String value = raw.strip();
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }

    private static TodoStatus parseStatus(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "open" -> TodoStatus.OPEN;
            case "closed" -> TodoStatus.CLOSED;
            default -> null;
        };
    }

    private static String titleOf(String titleRaw, String heading, String relativePath) {
        if (titleRaw != null && !titleRaw.isBlank()) {
            return titleRaw.strip();
        }
        if (heading != null && !heading.isBlank()) {
            String h = heading;
            if (h.startsWith("待办")) {
                String rest = h.substring("待办".length()).strip();
                if (rest.startsWith("·")) {
                    rest = rest.substring(1).strip();
                }
                if (!rest.isBlank()) {
                    return rest;
                }
            }
            return h;
        }
        return slugTitle(relativePath);
    }

    private static String slugTitle(String relativePath) {
        String name = relativePath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        int dash = name.lastIndexOf('-');
        if (dash >= 0 && dash < name.length() - 1) {
            return name.substring(dash + 1);
        }
        return name;
    }

    private static Field field(String line) {
        if (!line.startsWith("- ")) {
            return null;
        }
        String body = line.substring(2);
        int colon = body.indexOf('：');
        if (colon < 0) {
            colon = body.indexOf(':');
        }
        if (colon < 0) {
            return null;
        }
        return new Field(body.substring(0, colon).strip(), body.substring(colon + 1).strip());
    }

    private record Field(String name, String value) {
    }
}
