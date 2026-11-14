package com.mordor.kelly.kelsy.todo;

import java.time.*;
import java.util.*;

/** The database is the sole authority for tasks; article sources remain files. */
public record Todo(UUID uid, String title, String descriptionMd, TodoStatus status, LocalDate dueDate,
                   int priority, Instant createdAt, Instant updatedAt, Instant completedAt,
                   Instant deletedAt, long version, List<Source> sources) {
    public Todo { sources = List.copyOf(sources); }
    public record Source(UUID documentUid, String lastKnownPath, String sourceHash, String heading) {}
    public TodoCard card() { return new TodoCard(title, dueDate, status, "todo:" + uid); }
    public String markdown() {
        return "# " + title + "\n\n- 状态：" + status.name().toLowerCase(Locale.ROOT)
            + "\n- 截止：" + (dueDate == null ? "未设置" : dueDate) + "\n- 优先级：" + priority
            + "\n- 版本：" + version + "\n\n" + descriptionMd;
    }
}
