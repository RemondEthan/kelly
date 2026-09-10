package com.mordor.kelly.kelsy.todo;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoReminderPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);

    @Test
    void windowStartsThreeDaysBeforeDue() {
        assertFalse(TodoReminderPolicy.inWindow(open("远", LocalDate.of(2026, 9, 8)), TODAY));
        assertTrue(TodoReminderPolicy.inWindow(open("临界", LocalDate.of(2026, 9, 7)), TODAY));
        assertTrue(TodoReminderPolicy.inWindow(open("当天", TODAY), TODAY));
        assertTrue(TodoReminderPolicy.inWindow(open("逾期", LocalDate.of(2026, 9, 1)), TODAY));
        assertFalse(TodoReminderPolicy.inWindow(
                new TodoCard("关了", TODAY, TodoStatus.CLOSED, "knowledge/todos/x.md"), TODAY));
    }

    @Test
    void sortsByDueThenTitle() {
        var a = open("周报", LocalDate.of(2026, 9, 12));
        var b = open("申请", LocalDate.of(2026, 9, 10));
        var c = open("补材料", LocalDate.of(2026, 9, 10));
        assertEquals(List.of(b, c, a), TodoReminderPolicy.sort(List.of(a, c, b)));
    }

    private static TodoCard open(String title, LocalDate due) {
        return new TodoCard(title, due, TodoStatus.OPEN, "knowledge/todos/" + title + ".md");
    }
}
