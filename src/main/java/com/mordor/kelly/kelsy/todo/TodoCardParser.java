/**
 * 待办卡片解析器。
 *
 * <p>从 Markdown 格式的待办文件中解析出 {@link TodoCard} 数据。
 * 支持从文件内容或文件名中提取标题、截止日期和状态。
 *
 * <p>解析规则：
 * <ul>
 *   <li><b>截止日期</b> - 从 {@code - 截止：2026-03-20} 格式的行中提取</li>
 *   <li><b>状态</b> - 从 {@code - 状态：open/closed} 格式的行中提取</li>
 *   <li><b>标题</b> - 按优先级从以下来源提取：
 *       <ol>
 *         <li>{@code - 标题：xxx} 格式的行</li>
 *         <li>{@code # 标题} 格式的标题行（去掉"待办 ·"前缀）</li>
 *         <li>从文件名中提取（去掉日期前缀和 .md 后缀）</li>
 *       </ol>
 *   </li>
 * </ul>
 *
 * <p>文件命名约定：
 * 待办卡片文件通常命名为 {@code 2026-03-20-产品评审.md}，
 * 解析器会从文件名中提取 "产品评审" 作为标题。
 *
 * @see TodoCard
 * @see TodoStatus
 */
package com.mordor.kelly.kelsy.todo;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

public final class TodoCardParser {

    /** 私有构造函数，防止实例化 */
    private TodoCardParser() {
    }

    /**
     * 解析 Markdown 内容为待办卡片。
     *
     * @param markdown     Markdown 文件内容
     * @param relativePath 文件的相对路径（用于文件名解析）
     * @return 解析后的待办卡片，解析失败时返回 Optional.empty()
     */
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

    /**
     * 规范化截止日期字符串（截取前 10 个字符，即 yyyy-MM-dd）。
     */
    private static String dueDate(String raw) {
        String value = raw.strip();
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }

    /**
     * 解析状态字符串为枚举值。
     *
     * @param raw 状态字符串（"open" 或 "closed"）
     * @return 对应的 TodoStatus，无法解析时返回 null
     */
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

    /**
     * 按优先级提取标题：标题字段 > 标题行 > 文件名。
     */
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

    /**
     * 从文件名中提取标题。
     * 文件名格式：2026-03-20-产品评审.md → "产品评审"
     */
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

    /**
     * 从 Markdown 行中解析字段（格式：- 字段名：值）。
     *
     * @param line Markdown 行
     * @return 解析出的字段，非字段行返回 null
     */
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

    /** 字段键值对 */
    private record Field(String name, String value) {
    }
}
