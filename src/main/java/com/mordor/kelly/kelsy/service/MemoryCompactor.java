package com.mordor.kelly.kelsy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MemoryCompactor {

    public static final int LIMIT_BYTES = 4096;

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern PEOPLE_OR_PROJECT =
            Pattern.compile("knowledge/(?:people|projects)/\\S+");

    public record InboxCard(String relativePath, String markdown) {
    }

    public record Result(String memoryMarkdown, List<InboxCard> inboxCards, boolean backup) {
    }

    private MemoryCompactor() {
    }

    public static Result compact(String memoryMarkdown, LocalDate today) {
        String text = memoryMarkdown == null ? "" : memoryMarkdown;
        boolean backup = byteLength(text) > LIMIT_BYTES;
        if (!backup) {
            return new Result(text, List.of(), false);
        }
        LocalDate now = today == null ? LocalDate.now() : today;
        List<String> kept = new ArrayList<>();
        List<InboxCard> inbox = new ArrayList<>();
        boolean sawHeader = false;
        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (isHeader(line)) {
                kept.add("# Memory");
                sawHeader = true;
                continue;
            }
            if (!isListItem(line)) {
                continue;
            }
            keepOrDrop(line, now, kept, inbox);
        }
        if (!sawHeader) {
            kept.add(0, "# Memory");
        }
        kept = dedupePeopleProjects(kept);
        return new Result(joinMemory(kept), List.copyOf(inbox), true);
    }

    public static void apply(Path workspace, Result result) throws IOException {
        if (workspace == null || result == null) {
            return;
        }
        Path root = workspace.toAbsolutePath().normalize();
        for (InboxCard card : result.inboxCards()) {
            if (card == null || card.relativePath() == null || card.relativePath().isBlank()) {
                continue;
            }
            Path dest = root.resolve(card.relativePath()).normalize();
            if (!dest.startsWith(root)) {
                continue;
            }
            Files.createDirectories(dest.getParent());
            Files.writeString(dest, card.markdown() == null ? "" : card.markdown(), StandardCharsets.UTF_8);
        }
        Path memory = root.resolve("MEMORY.md");
        Path bak = root.resolve("MEMORY.md.bak");
        if (result.backup() && Files.exists(memory) && Files.notExists(bak)) {
            Files.copy(memory, bak);
        }
        Path tmp = root.resolve("MEMORY.md.tmp");
        Files.writeString(tmp, result.memoryMarkdown() == null ? "" : result.memoryMarkdown(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, memory, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, memory, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void keepOrDrop(String line, LocalDate today, List<String> kept, List<InboxCard> inbox) {
        List<LocalDate> dates = datesIn(line);
        if (dates.isEmpty()) {
            kept.add(line);
            return;
        }
        if (hasRecent(dates, today)) {
            kept.add(line);
            return;
        }
        if (line.contains("knowledge/")) {
            return;
        }
        inbox.add(toInboxCard(line, today));
    }

    private static InboxCard toInboxCard(String line, LocalDate today) {
        String slug = slug(line);
        String path = "knowledge/inbox/" + today + "-" + slug + ".md";
        String markdown = "# 收件箱\n\n" + line + "\n";
        return new InboxCard(path, markdown);
    }

    private static String slug(String line) {
        String body = line.startsWith("- ") ? line.substring(2) : line;
        body = ISO_DATE.matcher(body).replaceFirst("").strip();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.length() && sb.length() < 20; i++) {
            char c = body.charAt(i);
            if (isSafe(c)) {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "note" : sb.toString();
    }

    private static boolean isSafe(char c) {
        return Character.isLetterOrDigit(c)
                || c == '-'
                || c == '_'
                || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static List<LocalDate> datesIn(String line) {
        List<LocalDate> dates = new ArrayList<>();
        Matcher m = ISO_DATE.matcher(line);
        while (m.find()) {
            try {
                dates.add(LocalDate.parse(m.group()));
            } catch (DateTimeParseException ignored) {
            }
        }
        return dates;
    }

    private static boolean hasRecent(List<LocalDate> dates, LocalDate today) {
        LocalDate floor = today.minusDays(14);
        for (LocalDate date : dates) {
            if (!date.isBefore(floor)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> dedupePeopleProjects(List<String> lines) {
        Map<String, Integer> last = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String path = peopleOrProjectPath(lines.get(i));
            if (path != null) {
                last.put(path, i);
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String path = peopleOrProjectPath(lines.get(i));
            if (path != null && last.get(path) != i) {
                continue;
            }
            out.add(lines.get(i));
        }
        return out;
    }

    private static String peopleOrProjectPath(String line) {
        Matcher m = PEOPLE_OR_PROJECT.matcher(line);
        return m.find() ? m.group() : null;
    }

    private static boolean isHeader(String line) {
        return "# Memory".equals(line);
    }

    private static boolean isListItem(String line) {
        return line.startsWith("- ");
    }

    private static String joinMemory(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        boolean needBlankAfterHeader = false;
        for (String line : lines) {
            if (isHeader(line)) {
                sb.append(line).append('\n');
                needBlankAfterHeader = true;
                continue;
            }
            if (needBlankAfterHeader) {
                sb.append('\n');
                needBlankAfterHeader = false;
            }
            sb.append(line).append('\n');
        }
        if (needBlankAfterHeader) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
