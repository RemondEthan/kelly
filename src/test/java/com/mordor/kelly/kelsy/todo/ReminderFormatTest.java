package com.mordor.kelly.kelsy.todo;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderFormatTest {

    @Test
    void roundTripKeepsPathAndOverdue() {
        LocalDate today = LocalDate.of(2026, 9, 11);
        var cards = List.of(
                new TodoCard("申请 licence", LocalDate.of(2026, 9, 10),
                        TodoStatus.OPEN, "knowledge/todos/2026-09-10-申请-licence.md"),
                new TodoCard("发周报", LocalDate.of(2026, 9, 12),
                        TodoStatus.OPEN, "knowledge/todos/2026-09-12-发周报.md"));
        String text = ReminderFormat.encode(cards, today);
        assertTrue(text.startsWith("还有 2 条待办待处理"));
        var items = ReminderFormat.parse(text).orElseThrow();
        assertEquals(2, items.size());
        assertEquals("knowledge/todos/2026-09-10-申请-licence.md", items.get(0).relativePath());
        assertTrue(items.get(0).overdue());
        assertEquals("发周报", items.get(1).title());
        assertEquals(LocalDate.of(2026, 9, 12), items.get(1).due());
        assertEquals(false, items.get(1).overdue());
    }

    @Test
    void parseRejectsPlainChat() {
        assertTrue(ReminderFormat.parse("今天下午有会").isEmpty());
        assertTrue(ReminderFormat.parse(null).isEmpty());
    }
}
