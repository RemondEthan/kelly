package com.mordor.kelly.kelsy.service;

import java.nio.file.Path;

public record CardFields(String type, String who, String date, String title, String aliases, String status) {

    public static CardFields parse(String relativePath, String markdown) {
        String type = "";
        String who = "";
        String date = "";
        String heading = "";
        String topic = "";
        String aliases = "";
        String status = "";
        String text = markdown == null ? "" : markdown;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (heading.isEmpty() && line.startsWith("# ")) {
                heading = line.substring(2).strip();
                continue;
            }
            Field field = field(line);
            if (field == null) {
                continue;
            }
            switch (field.name()) {
                case "类型" -> type = field.value();
                case "谁" -> who = field.value();
                case "日期", "截止" -> date = field.value();
                case "别名" -> aliases = field.value();
                case "状态" -> status = field.value();
                case "主题" -> topic = field.value();
                default -> {
                }
            }
        }
        String title = !heading.isEmpty() ? heading : !topic.isEmpty() ? topic : fileTitle(relativePath);
        return new CardFields(type, who, date, title, aliases, status);
    }

    private static String fileTitle(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String name = Path.of(relativePath).getFileName().toString();
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private static Field field(String line) {
        if (!line.startsWith("- ")) {
            return null;
        }
        String body = line.substring(2);
        int colon = indexOfColon(body);
        if (colon < 0) {
            return null;
        }
        return new Field(body.substring(0, colon).strip(), body.substring(colon + 1).strip());
    }

    private static int indexOfColon(String body) {
        int full = body.indexOf('：');
        int half = body.indexOf(':');
        if (full < 0) {
            return half;
        }
        if (half < 0) {
            return full;
        }
        return Math.min(full, half);
    }

    private record Field(String name, String value) {
    }
}
