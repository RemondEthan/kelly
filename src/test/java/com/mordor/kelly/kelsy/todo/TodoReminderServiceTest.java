package com.mordor.kelly.kelsy.todo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoReminderServiceTest {

    @TempDir Path root;

    @Test
    void loginAt1001FiresLoginAndTenOnce() throws Exception {
        writeOpen("knowledge/todos/2026-09-07-临界.md", "临界", "2026-09-07");
        TodoReminderService svc = new TodoReminderService(root);
        var now = LocalDateTime.of(2026, 9, 4, 10, 1);
        var batch = svc.evaluate(now).orElseThrow();
        assertEquals(1, batch.todos().size());
        assertEquals(EnumSet.of(ReminderSlot.LOGIN, ReminderSlot.TEN), batch.slots());
        svc.commit(batch, now.toLocalDate());
        assertTrue(svc.evaluate(now).isEmpty());
    }

    @Test
    void loginAt0900OnlyLoginThenTenFiresLater() throws Exception {
        writeOpen("knowledge/todos/2026-09-04-当天.md", "当天", "2026-09-04");
        TodoReminderService svc = new TodoReminderService(root);
        var morning = LocalDateTime.of(2026, 9, 4, 9, 0);
        var first = svc.evaluate(morning).orElseThrow();
        assertEquals(EnumSet.of(ReminderSlot.LOGIN), first.slots());
        svc.commit(first, morning.toLocalDate());
        assertTrue(svc.evaluate(morning).isEmpty());
        var ten = LocalDateTime.of(2026, 9, 4, 10, 0);
        var second = svc.evaluate(ten).orElseThrow();
        assertEquals(EnumSet.of(ReminderSlot.TEN), second.slots());
    }

    @Test
    void emptyWindowDoesNotMark() throws Exception {
        writeOpen("knowledge/todos/2026-09-08-远.md", "远", "2026-09-08");
        TodoReminderService svc = new TodoReminderService(root);
        var now = LocalDateTime.of(2026, 9, 4, 10, 1);
        assertTrue(svc.evaluate(now).isEmpty());
        writeOpen("knowledge/todos/2026-09-07-临界.md", "临界", "2026-09-07");
        var later = svc.evaluate(now).orElseThrow();
        assertEquals(EnumSet.of(ReminderSlot.LOGIN, ReminderSlot.TEN), later.slots());
    }

    @Test
    void skipsClosedAndBadFiles() throws Exception {
        write("knowledge/todos/bad.md", "- 状态：open\n");
        write("knowledge/todos/2026-09-04-关.md", """
                - 截止：2026-09-04
                - 状态：closed
                - 标题：关
                """);
        TodoReminderService svc = new TodoReminderService(root);
        assertTrue(svc.evaluate(LocalDateTime.of(2026, 9, 4, 15, 0)).isEmpty());
    }

    @Test
    void nextClock() {
        assertEquals(LocalDateTime.of(2026, 9, 4, 10, 0),
                TodoReminderService.nextClock(LocalDateTime.of(2026, 9, 4, 9, 0)));
        assertEquals(LocalDateTime.of(2026, 9, 4, 14, 0),
                TodoReminderService.nextClock(LocalDateTime.of(2026, 9, 4, 10, 0)));
        assertEquals(LocalDateTime.of(2026, 9, 5, 10, 0),
                TodoReminderService.nextClock(LocalDateTime.of(2026, 9, 4, 14, 0)));
    }

    private void writeOpen(String rel, String title, String due) throws Exception {
        write(rel, "- 截止：" + due + "\n- 状态：open\n- 标题：" + title + "\n");
    }

    private void write(String rel, String body) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }
}
