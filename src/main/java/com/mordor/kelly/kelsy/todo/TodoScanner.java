package com.mordor.kelly.kelsy.todo;

import com.mordor.kelly.common.Diag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class TodoScanner {

    private TodoScanner() {
    }

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
            Diag.warn("todo", "无法扫描待办目录: %s", e.getMessage());
        }
        return List.copyOf(out);
    }

    private static void add(Path knowledgeRoot, Path file, List<TodoCard> out) {
        String rel = knowledgeRoot.relativize(file).toString().replace('\\', '/');
        try {
            Optional<TodoCard> card = TodoCardParser.parse(Files.readString(file), rel);
            if (card.isEmpty()) {
                Diag.warn("todo", "跳过无法解析的待办: %s", rel);
                return;
            }
            out.add(card.get());
        } catch (IOException e) {
            Diag.warn("todo", "读取待办失败 %s: %s", rel, e.getMessage());
        }
    }
}
